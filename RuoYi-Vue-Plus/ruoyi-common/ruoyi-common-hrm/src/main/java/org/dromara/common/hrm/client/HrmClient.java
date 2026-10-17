package org.dromara.common.hrm.client;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.hrm.config.HrmProperties;
import org.dromara.common.hrm.domain.HrmQueryResult;
import org.dromara.common.hrm.domain.HrmRecord;
import org.dromara.common.hrm.domain.HrmUserQuery;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 泛微 HrmService 的四个只读查询客户端，使用 WSDL 定义的 SOAP 1.1 document/literal 协议。 */
@Slf4j
public class HrmClient implements AutoCloseable {

    private static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String SERVICE_NS = "http://localhost/services/HrmService";
    private static final String BEAN_NS = "http://webservice.hrm.weaver";
    private static final String ACTION_PREFIX = "urn:weaver.hrm.webservice.HrmService.";
    private static final int RESPONSE_LOG_LIMIT = 32768;
    private static final Set<String> ORGANIZATION_LOG_FIELDS = Set.of(
        "_subcompanyid", "_supsubcompanyid", "_departmentid", "_supdepartmentid",
        "_jobtitleid", "_fullname", "_shortname", "_code");

    private final HrmProperties properties;
    private final HttpClient httpClient;

    /** 创建可复用的 HTTP 客户端；创建对象不会调用 OA。 */
    public HrmClient(HrmProperties properties) {
        this.properties = Objects.requireNonNull(properties);
        validateTimeouts();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /** 查询全部分部，保留 OA 原始字段和 null 结果状态。 */
    public HrmQueryResult getHrmSubcompanyInfo() {
        return invoke("getHrmSubcompanyInfo", "SubCompanyBean");
    }

    /**
     * 查询部门。
     *
     * @param subcompanyId 分部 ID，多个用逗号分隔，为空时不按分部筛选
     * @return OA 原始查询结果
     */
    public HrmQueryResult getHrmDepartmentInfo(String subcompanyId) {
        return invoke("getHrmDepartmentInfo", "DepartmentBean", subcompanyId);
    }

    /**
     * 查询岗位。
     *
     * @param subcompanyId 分部 ID，多个用逗号分隔
     * @param departmentId 部门 ID，多个用逗号分隔
     * @return OA 原始查询结果
     */
    public HrmQueryResult getHrmJobTitleInfo(String subcompanyId, String departmentId) {
        return invoke("getHrmJobTitleInfo", "JobTitleBean", subcompanyId, departmentId);
    }

    /**
     * 按条件查询人员，密码在解析时丢弃，其他字段保持 OA 原值。
     *
     * @param query 查询条件，不能为空；首次联调建议指定人员编码或部门范围
     * @return OA 原始查询结果，不应直接输出到日志或对外接口
     */
    public HrmQueryResult getHrmUserInfo(HrmUserQuery query) {
        Objects.requireNonNull(query, "人员查询条件不能为空");
        return invoke("getHrmUserInfo", "UserBean", query.getWorkcode(), query.getSubcompanyId(),
            query.getDepartmentId(), query.getJobtitleId(),
            query.getLastChangeDate() == null ? "" : query.getLastChangeDate().toString());
    }

    private HrmQueryResult invoke(String method, String recordType, String... filters) {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        try {
            HrmQueryResult result = invokeRequest(requestId, started, method, recordType, filters);
            List<String> fieldNames = result.records().stream().filter(Objects::nonNull)
                .findFirst().map(record -> record.fields().keySet().stream().limit(20)
                    .map(this::safeLogText).toList()).orElse(List.of());
            log.info("HRM 解析成功 requestId={} method={} elapsedMs={} nullResult={} recordCount={} firstRecordFields={}",
                requestId, method, elapsedMillis(started), result.nullResult(), result.records().size(), fieldNames);
            return result;
        } catch (HrmClientException exception) {
            // 底层异常消息可能含 XML 原文或人员值，只记录异常类型和客户端脱敏说明。
            log.error("HRM 调用失败 requestId={} method={} elapsedMs={} reason={} causeTypes={}",
                requestId, method, elapsedMillis(started), exception.getMessage(), causeTypes(exception));
            throw exception;
        }
    }

    private HrmQueryResult invokeRequest(String requestId, long started, String method, String recordType, String... filters) {
        URI endpoint = validateConfiguration();
        String[] parameters = new String[filters.length + 1];
        parameters[0] = properties.getClientIp();
        System.arraycopy(filters, 0, parameters, 1, filters.length);
        log.info("HRM 请求开始 requestId={} method={} endpoint={} clientIp={} connectTimeoutSeconds={} readTimeoutSeconds={} "
                + "soapAction={} serviceNamespace={} beanNamespace={} parameters={}",
            requestId, method, safeLogText(endpoint.toString()), properties.getClientIp(), properties.getConnectTimeoutSeconds(),
            properties.getReadTimeoutSeconds(), ACTION_PREFIX + method, SERVICE_NS, BEAN_NS, parameterSummary(parameters));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
            .header("Content-Type", "text/xml; charset=utf-8")
            .header("SOAPAction", "\"" + ACTION_PREFIX + method + "\"")
            .POST(HttpRequest.BodyPublishers.ofString(envelope(method, parameters), StandardCharsets.UTF_8))
            .build();
        CompletableFuture<HttpResponse<byte[]>> exchange = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        try {
            // JDK 请求定时器在响应头到达后结束，这里同时限制整个响应正文的接收时间。
            HttpResponse<byte[]> response = exchange.get(properties.getReadTimeoutSeconds(), TimeUnit.SECONDS);
            log.info("HRM 响应接收 requestId={} method={} status={} elapsedMs={} contentType={} responseBytes={}",
                requestId, method, response.statusCode(), elapsedMillis(started),
                safeLogText(response.headers().firstValue("Content-Type").orElse("<none>")), response.body().length);
            return parse(requestId, method, recordType, response.statusCode(), response.body());
        } catch (TimeoutException exception) {
            exchange.cancel(true);
            throw new HrmClientException(method + " 调用 OA 超时", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            String reason = cause instanceof HttpTimeoutException ? "超时" : "网络失败";
            throw new HrmClientException(method + " 调用 OA " + reason, cause);
        } catch (InterruptedException exception) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new HrmClientException(method + " 调用 OA 被中断", exception);
        }
    }

    private URI validateConfiguration() {
        validateTimeouts();
        String ip = properties.getClientIp();
        if (ip == null || ip.length() > 15 || !ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            throw new HrmClientException("hrm.client-ip 必须是 OA 白名单中的客户端 IPv4 地址");
        }
        for (String part : ip.split("\\.")) {
            if (Integer.parseInt(part) > 255) {
                throw new HrmClientException("hrm.client-ip 不是有效的 IPv4 地址");
            }
        }
        try {
            URI endpoint = URI.create(properties.getEndpoint() == null ? "" : properties.getEndpoint());
            if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return endpoint;
        } catch (IllegalArgumentException exception) {
            throw new HrmClientException("hrm.endpoint 必须是有效的 HTTP/HTTPS 服务地址，不能包含 ?wsdl");
        }
    }

    private void validateTimeouts() {
        if (properties.getConnectTimeoutSeconds() <= 0 || properties.getReadTimeoutSeconds() <= 0) {
            throw new HrmClientException("HRM 连接和请求超时秒数必须大于零");
        }
    }

    private String envelope(String method, String[] parameters) {
        try {
            StringWriter output = new StringWriter();
            XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("soap", "Envelope", SOAP_NS);
            writer.writeNamespace("soap", SOAP_NS);
            writer.writeNamespace("hrm", SERVICE_NS);
            writer.writeStartElement("soap", "Body", SOAP_NS);
            writer.writeStartElement("hrm", method, SERVICE_NS);
            for (int index = 0; index < parameters.length; index++) {
                writer.writeStartElement("hrm", "in" + index, SERVICE_NS);
                writer.writeCharacters(parameters[index] == null ? "" : parameters[index]);
                writer.writeEndElement();
            }
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.close();
            return output.toString();
        } catch (XMLStreamException exception) {
            throw new HrmClientException("无法构造 HRM SOAP 请求", exception);
        }
    }

    private HrmQueryResult parse(String requestId, String method, String recordType, int status, byte[] bytes) {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            document = builder.parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            int line = exception instanceof SAXParseException parseException ? parseException.getLineNumber() : -1;
            int column = exception instanceof SAXParseException parseException ? parseException.getColumnNumber() : -1;
            log.error("HRM XML 解析失败 requestId={} method={} status={} responseBytes={} parserErrorType={} line={} column={}",
                requestId, method, status, bytes.length, exception.getClass().getName(), line, column);
            if (status < 200 || status >= 300) {
                throw httpFailure(method, status);
            }
            throw new HrmClientException(method + " 返回了无效或不安全的 XML", exception);
        }
        Element envelope = document.getDocumentElement();
        logResponseBody(requestId, method, recordType, envelope);
        Element body = isElement(envelope, SOAP_NS, "Envelope") ? child(envelope, SOAP_NS, "Body") : null;
        Element fault = body == null ? null : child(body, SOAP_NS, "Fault");
        if (fault != null) {
            log.error("HRM SOAP Fault requestId={} method={} status={} structure={}",
                requestId, method, status, elementSummary(fault));
            throw new HrmClientException(method + " 返回 SOAP Fault，请联系接口方检查 OA 日志");
        }
        if (status < 200 || status >= 300) {
            throw httpFailure(method, status);
        }
        Element response = body == null ? null : child(body, SERVICE_NS, method + "Response");
        Element out = response == null ? null : child(response, SERVICE_NS, "out");
        if (out == null) {
            log.error("HRM 响应结构异常 requestId={} method={} expectedResponse={} expectedNamespace={} envelope={} body={} response={}",
                requestId, method, method + "Response", SERVICE_NS,
                elementSummary(envelope), elementSummary(body), elementSummary(response));
            throw new HrmClientException(method + " 返回结构与 WSDL 不符，缺少对应 Response/out");
        }
        if (isNil(out)) {
            return new HrmQueryResult(method, true, List.of());
        }
        List<HrmRecord> records = new ArrayList<>();
        int recordIndex = 0;
        for (Element bean : children(out)) {
            recordIndex++;
            if (!isElement(bean, BEAN_NS, recordType)) {
                log.error("HRM 记录类型异常 requestId={} method={} recordIndex={} expectedType={} expectedNamespace={} structure={}",
                    requestId, method, recordIndex, recordType, BEAN_NS, elementSummary(bean));
                throw new HrmClientException(method + " 返回了非预期记录类型");
            }
            if (isNil(bean)) {
                records.add(null);
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            int fieldIndex = 0;
            for (Element field : children(bean)) {
                fieldIndex++;
                String name = field.getLocalName();
                // OA 返回密码不属于组织与人员资料对接所需内容，直接丢弃。
                if ("password".equalsIgnoreCase(name) || "_password".equalsIgnoreCase(name)) {
                    continue;
                }
                // OA 实际响应会混用 Bean 命名空间和空命名空间，仅在字段层兼容此差异。
                String namespace = field.getNamespaceURI();
                boolean namespaceMismatch = namespace != null && !namespace.isEmpty() && !BEAN_NS.equals(namespace);
                boolean nestedElements = !children(field).isEmpty();
                boolean duplicate = fields.containsKey(name);
                if (namespaceMismatch || nestedElements || duplicate) {
                    log.error("HRM 字段结构异常 requestId={} method={} recordIndex={} fieldIndex={} expectedNamespace={} "
                            + "namespaceMismatch={} nestedElements={} duplicate={} field={} bean={}",
                        requestId, method, recordIndex, fieldIndex, BEAN_NS,
                        namespaceMismatch, nestedElements, duplicate, elementSummary(field), elementSummary(bean));
                    throw new HrmClientException(method + " 返回了不符合 WSDL 的字段结构");
                }
                fields.put(name, isNil(field) ? null : field.getTextContent());
            }
            records.add(new HrmRecord(fields));
        }
        if (records.isEmpty() && !out.getTextContent().isBlank()) {
            log.error("HRM 非预期文本结果 requestId={} method={} structure={}", requestId, method, elementSummary(out));
            throw new HrmClientException(method + " 返回了非预期的文本结果");
        }
        return new HrmQueryResult(method, false, records);
    }

    /** 使用副本脱敏，保留命名空间证据，不影响后续严格解析。 */
    private void logResponseBody(String requestId, String method, String recordType, Element envelope) {
        if (!properties.isLogResponseBody()) {
            return;
        }
        Element copy = (Element) envelope.cloneNode(true);
        redactResponse(copy, recordType);
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter output = new StringWriter();
            transformer.transform(new DOMSource(copy), new StreamResult(output));
            String xml = output.toString().replaceAll("[\\p{Cntrl}\\u2028\\u2029]", "_");
            log.info("HRM 响应正文 requestId={} method={} redacted=true truncated={} xml={}",
                requestId, method, xml.length() > RESPONSE_LOG_LIMIT,
                xml.substring(0, Math.min(xml.length(), RESPONSE_LOG_LIMIT)));
        } catch (TransformerException exception) {
            log.warn("HRM 响应正文日志失败 requestId={} method={} errorType={}",
                requestId, method, exception.getClass().getName());
        }
    }

    private void redactResponse(Element element, String recordType) {
        // 仅组织 Bean 的已公开文本字段保留值；人员、Fault、未知字段和属性一律脱敏。
        boolean keepText = !"UserBean".equals(recordType)
            && ORGANIZATION_LOG_FIELDS.contains(element.getLocalName())
            && element.getParentNode() instanceof Element parent
            && isElement(parent, BEAN_NS, recordType) && children(element).isEmpty();
        var attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Node attribute = attributes.item(index);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())
                && !(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI.equals(attribute.getNamespaceURI())
                    && ("nil".equals(attribute.getLocalName()) || "type".equals(attribute.getLocalName())))) {
                attribute.setNodeValue("[REDACTED]");
            }
        }
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                redactResponse(child, recordType);
            } else if (node.getNodeType() == Node.COMMENT_NODE || node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE
                || (!keepText && node.getNodeValue() != null && !node.getNodeValue().isBlank())) {
                node.setNodeValue("[REDACTED]");
            }
        }
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    /** 仅记录筛选项位置和是否填写，不将工号、组织筛选值放入日志。 */
    private List<String> parameterSummary(String[] parameters) {
        List<String> summary = new ArrayList<>();
        summary.add("in0=" + parameters[0]);
        for (int index = 1; index < parameters.length; index++) {
            String value = parameters[index];
            summary.add("in" + index + "=" + (value == null || value.isEmpty() ? "<empty>" : "<redacted>"));
        }
        return summary;
    }

    /** 只展示节点名称、命名空间、nil 状态和直接子节点，省略文本及其他属性值。 */
    private String elementSummary(Element element) {
        if (element == null) {
            return "<missing>";
        }
        List<Element> elements = children(element);
        List<String> names = elements.stream().limit(20).map(child -> "{" + safeLogText(child.getNamespaceURI())
            + "}" + safeLogText(child.getLocalName())).toList();
        return "name=" + safeLogText(element.getLocalName()) + " namespace=" + safeLogText(element.getNamespaceURI())
            + " nil=" + isNil(element) + " childCount=" + elements.size() + " children=" + names
            + (elements.size() > 20 ? " <truncated>" : "");
    }

    private String safeLogText(String value) {
        if (value == null) {
            return "<none>";
        }
        String bounded = value.substring(0, Math.min(value.length(), 200));
        return bounded.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", "_") + (value.length() > 200 ? "<truncated>" : "");
    }

    private List<String> causeTypes(Throwable exception) {
        List<String> types = new ArrayList<>();
        for (Throwable cause = exception; cause != null && types.size() < 5; cause = cause.getCause()) {
            types.add(cause.getClass().getName());
        }
        return types;
    }

    private HrmClientException httpFailure(String method, int status) {
        return new HrmClientException(method + " 调用 OA 返回 HTTP " + status);
    }

    private boolean isNil(Element element) {
        String nil = element.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil");
        return "true".equals(nil) || "1".equals(nil);
    }

    private boolean isElement(Element element, String namespace, String name) {
        return namespace.equals(element.getNamespaceURI()) && name.equals(element.getLocalName());
    }

    private Element child(Element parent, String namespace, String name) {
        for (Element element : children(parent)) {
            if (isElement(element, namespace, name)) {
                return element;
            }
        }
        return null;
    }

    private List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    /** 应用关闭时取消在途只读请求并释放 HTTP 客户端资源。 */
    @PreDestroy
    @Override
    public void close() {
        httpClient.shutdownNow();
    }
}
