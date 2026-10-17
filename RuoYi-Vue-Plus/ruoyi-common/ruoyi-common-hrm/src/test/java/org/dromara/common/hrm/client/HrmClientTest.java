package org.dromara.common.hrm.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.dromara.common.hrm.config.HrmProperties;
import org.dromara.common.hrm.domain.HrmQueryResult;
import org.dromara.common.hrm.domain.HrmUserQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HrmClientTest {

    private static final String SERVICE_NS = "http://localhost/services/HrmService";
    private static final String BEAN_NS = "http://webservice.hrm.weaver";
    private HttpServer server;
    private final List<String> bodies = new ArrayList<>();
    private final List<String> actions = new ArrayList<>();
    private final List<String> contentTypes = new ArrayList<>();
    private final List<HrmClient> clients = new ArrayList<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(HrmClient.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void stopServer() {
        logger.detachAppender(logs);
        logs.stop();
        clients.forEach(HrmClient::close);
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void logsCorrelatedRequestResponseAndResultWithoutFieldValues() throws IOException {
        client("getHrmSubcompanyInfo", 200,
            "<b:SubCompanyBean><b:_fullname>private-company</b:_fullname></b:SubCompanyBean>")
            .getHrmSubcompanyInfo();

        String output = logOutput();
        assertThat(output).contains("HRM 请求开始", "HRM 响应接收", "HRM 解析成功",
            "clientIp=192.0.2.10", "status=200", "recordCount=1", "_fullname", "elapsedMs=")
            .doesNotContain("private-company", "HRM 响应正文");
        List<String> requestIds = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
            .filter(message -> message.contains("requestId="))
            .map(message -> message.split("requestId=", 2)[1].split("[ ,]", 2)[0]).toList();
        assertThat(requestIds).hasSize(3);
        assertThat(requestIds.stream().distinct()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<x:_fullname xmlns:x='urn:unexpected'>private-field-value</x:_fullname>",
        "<b:_fullname><b:item>private-field-value</b:item></b:_fullname>",
        "<_fullname><item>private-field-value</item></_fullname>",
        "<b:_fullname>first-private-value</b:_fullname><b:_fullname>private-field-value</b:_fullname>",
        "<b:_fullname>first-private-value</b:_fullname><_fullname>private-field-value</_fullname>"
    })
    void logsExactFieldStructureFailureWithoutValues(String field) throws IOException {
        HrmClient client = client("getHrmSubcompanyInfo", 200,
            "<b:SubCompanyBean><b:_subcompanyid>private-id</b:_subcompanyid></b:SubCompanyBean>"
                + "<b:SubCompanyBean>" + field + "</b:SubCompanyBean>");

        assertThatThrownBy(client::getHrmSubcompanyInfo)
            .hasMessage("getHrmSubcompanyInfo 返回了不符合 WSDL 的字段结构");
        String output = logOutput();
        assertThat(output).contains("HRM 字段结构异常", "recordIndex=2", "fieldIndex=", "_fullname",
            "namespaceMismatch=", "nestedElements=", "duplicate=", "HRM 调用失败")
            .doesNotContain("private-field-value", "first-private-value", "private-id");
        if (field.startsWith("<x:")) {
            assertThat(output).contains("namespaceMismatch=true", "namespace=urn:unexpected");
        } else if (field.contains("<b:item>") || field.contains("<item>")) {
            assertThat(output).contains("nestedElements=true", "item");
        } else {
            assertThat(output).contains("duplicate=true", "fieldIndex=2");
        }
    }

    @Test
    void logsOrganizationXmlWithoutChangingParsedValues() throws IOException {
        HrmClient client = client("getHrmSubcompanyInfo", 200,
            "<b:SubCompanyBean><b:_canceled>false</b:_canceled><_code>ORG001</_code>"
                + "<_fullname>测试组织</_fullname><password>secret-password</password>"
                + "<!--secret-comment--></b:SubCompanyBean>");
        assertThat(client.getHrmSubcompanyInfo().records().getFirst().fields()).containsEntry("_code", "ORG001");
        assertThat(logOutput()).doesNotContain("HRM 响应正文", "ORG001", "测试组织");

        logs.list.clear();
        responseLogEnabled = true;
        HrmClient loggingClient = new HrmClient(properties());
        clients.add(loggingClient);
        assertThat(loggingClient.getHrmSubcompanyInfo().records().getFirst().fields())
            .containsEntry("_code", "ORG001").containsEntry("_fullname", "测试组织").doesNotContainKey("password");
        assertThat(logOutput()).contains("HRM 响应正文", "redacted=true", "truncated=false",
            "<_code>ORG001</_code>", "<_fullname>测试组织</_fullname>", "xmlns:b=", BEAN_NS,
            "[REDACTED]", "HRM 解析成功")
            .doesNotContain("secret-password", "secret-comment", "HRM 字段结构异常");
    }

    @ParameterizedTest
    @CsvSource({
        "getHrmSubcompanyInfo,SubCompanyBean,_subcompanyid,_fullname",
        "getHrmDepartmentInfo,DepartmentBean,_departmentid,_fullname",
        "getHrmJobTitleInfo,JobTitleBean,_jobtitleid,_fullname",
        "getHrmUserInfo,UserBean,userid,lastname"
    })
    void acceptsMixedFieldNamespacesForAllQueries(String method, String bean, String id, String name) throws IOException {
        HrmClient client = client(method, 200, "<b:" + bean + "><b:" + id + ">001</b:" + id + ">"
            + "<" + name + ">测试记录</" + name + "><_code xsi:nil='true'/><action/>"
            + "<password>secret-password</password></b:" + bean + ">");
        HrmQueryResult result = switch (method) {
            case "getHrmSubcompanyInfo" -> client.getHrmSubcompanyInfo();
            case "getHrmDepartmentInfo" -> client.getHrmDepartmentInfo("");
            case "getHrmJobTitleInfo" -> client.getHrmJobTitleInfo("", "");
            case "getHrmUserInfo" -> client.getHrmUserInfo(new HrmUserQuery());
            default -> throw new IllegalArgumentException(method);
        };
        assertThat(result.nullResult()).isFalse();
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().getFirst().fields()).containsEntry(id, "001")
            .containsEntry(name, "测试记录").containsEntry("_code", null).containsEntry("action", "")
            .doesNotContainKey("password");
        assertThat(logOutput()).contains("HRM 解析成功").doesNotContain("HRM 字段结构异常", "secret-password");
    }

    @Test
    void responseLoggingRedactsUserValuesAndAttributes() throws IOException {
        responseLogEnabled = true;
        HrmQueryResult result = client("getHrmUserInfo", 200,
            "<b:UserBean><b:lastname>private-person</b:lastname>"
                + "<b:password note='private-attribute'>private-password</b:password>"
                + "<b:mobile>private-phone</b:mobile></b:UserBean>")
            .getHrmUserInfo(new HrmUserQuery());
        assertThat(logOutput()).contains("HRM 响应正文", "[REDACTED]", "lastname", "password", "mobile")
            .doesNotContain("private-person", "private-password", "private-phone", "private-attribute");
        assertThat(result.records().getFirst().fields()).containsEntry("lastname", "private-person")
            .doesNotContainKey("password");
    }

    @Test
    void responseLoggingRedactsSoapFaultAndBoundsOutput() throws IOException {
        responseLogEnabled = true;
        HrmClient faultClient = clientWithBody(500, envelope(
            "<s:Fault><faultstring>private-fault</faultstring></s:Fault>"));
        assertThatThrownBy(faultClient::getHrmSubcompanyInfo).hasMessageContaining("SOAP Fault");
        assertThat(logOutput()).contains("HRM 响应正文", "[REDACTED]").doesNotContain("private-fault");
        server.stop(0);
        logs.list.clear();
        client("getHrmSubcompanyInfo", 200,
            "<b:SubCompanyBean><b:_fullname>" + "a".repeat(40000)
                + "</b:_fullname></b:SubCompanyBean>").getHrmSubcompanyInfo();
        String message = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
            .filter(value -> value.startsWith("HRM 响应正文 ")).findFirst().orElseThrow();
        assertThat(message).contains("truncated=true");
        assertThat(message.split(" xml=", 2)[1]).hasSize(32768);
    }

    @Test
    void logsUserFilterPresenceAndSoapFaultWithoutSecrets() throws IOException {
        HrmUserQuery query = new HrmUserQuery();
        query.setWorkcode("private-workcode");
        HrmClient client = clientWithBody(500, envelope("<s:Fault><faultcode>s:Server</faultcode>"
            + "<faultstring>private-fault</faultstring></s:Fault>"));
        assertThatThrownBy(() -> client.getHrmUserInfo(query)).hasMessageContaining("SOAP Fault");

        assertThat(logOutput()).contains("in1=<redacted>", "status=500", "HRM SOAP Fault", "faultstring")
            .doesNotContain("private-workcode", "private-fault");
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    void logsInvalidXmlWithoutParserMessageOrRawBody() throws IOException {
        HrmClient client = clientWithBody(200, envelope("<private-value>private-body</wrong-tag>"));
        assertThatThrownBy(client::getHrmSubcompanyInfo).hasMessageContaining("无效或不安全的 XML");

        assertThat(logOutput()).contains("HRM XML 解析失败", "parserErrorType=", "line=", "column=", "HRM 调用失败")
            .doesNotContain("private-value", "private-body", "wrong-tag");
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    private String logOutput() {
        return String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    void sendsQualifiedSoap11ParametersAndPreservesOriginalFields() throws IOException {
        HrmClient client = client("getHrmSubcompanyInfo", 200,
            "<b:SubCompanyBean><b:_subcompanyid>001</b:_subcompanyid>"
                + "<b:_fullname>研发 &amp; 技术</b:_fullname><b:_code xsi:nil=\"true\"/>"
                + "</b:SubCompanyBean>");

        HrmQueryResult result = client.getHrmSubcompanyInfo();

        assertThat(result.nullResult()).isFalse();
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().getFirst().fields()).containsEntry("_subcompanyid", "001")
            .containsEntry("_fullname", "研发 & 技术").containsEntry("_code", null);
        assertThat(bodies.getFirst()).contains(SERVICE_NS, "getHrmSubcompanyInfo", "in0", "192.0.2.10");
        assertThat(actions).containsExactly("\"urn:weaver.hrm.webservice.HrmService.getHrmSubcompanyInfo\"");
        assertThat(contentTypes.getFirst()).startsWith("text/xml");
        assertThatThrownBy(() -> result.records().getFirst().fields().put("x", "y"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sendsDepartmentFilterAsSecondParameter() throws IOException {
        client("getHrmDepartmentInfo", 200, "").getHrmDepartmentInfo("10,20");
        assertThat(bodies.getFirst()).contains("<hrm:in0>192.0.2.10</hrm:in0>", "<hrm:in1>10,20</hrm:in1>");
    }

    @Test
    void sendsJobTitleFiltersInWsdlOrderAndEscapesXml() throws IOException {
        client("getHrmJobTitleInfo", 200, "").getHrmJobTitleInfo("10", "A&B<1>");
        assertThat(bodies.getFirst()).contains("<hrm:in1>10</hrm:in1>", "<hrm:in2>A&amp;B&lt;1&gt;</hrm:in2>");
    }

    @Test
    void sendsAllSixUserParametersAndDiscardsPassword() throws IOException {
        HrmUserQuery query = new HrmUserQuery();
        query.setWorkcode("EMP001");
        query.setSubcompanyId("10");
        query.setDepartmentId("20");
        query.setJobtitleId("30");
        query.setLastChangeDate(LocalDate.of(2026, 10, 9));
        HrmQueryResult result = client("getHrmUserInfo", 200,
            "<b:UserBean><b:userid>7</b:userid><b:workcode>EMP001</b:workcode>"
                + "<b:lastname>测试人员</b:lastname><b:password>never-retain-this</b:password>"
                + "<b:dsporder>1.5</b:dsporder></b:UserBean>").getHrmUserInfo(query);

        assertThat(bodies.getFirst()).contains("<hrm:in1>EMP001</hrm:in1>", "<hrm:in2>10</hrm:in2>",
            "<hrm:in3>20</hrm:in3>", "<hrm:in4>30</hrm:in4>", "<hrm:in5>2026-10-09</hrm:in5>");
        assertThat(result.records().getFirst().fields()).containsEntry("userid", "7")
            .containsEntry("dsporder", "1.5").doesNotContainKey("password");
        assertThat(result.toString()).doesNotContain("测试人员", "EMP001", "never-retain-this");
        assertThat(result.records().toString()).doesNotContain("测试人员", "EMP001", "never-retain-this");
        assertThat(logOutput()).doesNotContain("测试人员", "EMP001", "never-retain-this");
    }

    @Test
    void sendsEmptyStringsForUnusedOptionalFilters() throws IOException {
        client("getHrmUserInfo", 200, "").getHrmUserInfo(new HrmUserQuery());
        assertThat(bodies.getFirst()).contains("<hrm:in1></hrm:in1>", "<hrm:in5></hrm:in5>");
    }

    @Test
    void distinguishesNullResultFromSuccessfulEmptyArray() throws IOException {
        HrmQueryResult result = clientWithBody(200, envelope(
            "<h:getHrmSubcompanyInfoResponse xmlns:h=\"" + SERVICE_NS + "\">"
                + "<h:out xsi:nil=\"true\"/></h:getHrmSubcompanyInfoResponse>"))
            .getHrmSubcompanyInfo();
        assertThat(result.nullResult()).isTrue();
        assertThat(result.records()).isEmpty();
    }

    @Test
    void recognizesSuccessfulEmptyArray() throws IOException {
        HrmQueryResult result = client("getHrmSubcompanyInfo", 200, "").getHrmSubcompanyInfo();
        assertThat(result.nullResult()).isFalse();
        assertThat(result.records()).isEmpty();
    }

    @Test
    void preservesNullableArrayItemsAndTheirPositionsAsAllowedByWsdl() throws IOException {
        HrmQueryResult result = client("getHrmSubcompanyInfo", 200,
            "<b:SubCompanyBean xsi:nil=\"true\"/><b:SubCompanyBean>"
                + "<b:_subcompanyid>002</b:_subcompanyid></b:SubCompanyBean>")
            .getHrmSubcompanyInfo();
        assertThat(result.nullResult()).isFalse();
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().getFirst()).isNull();
        assertThat(result.records().get(1).fields()).containsEntry("_subcompanyid", "002");
        assertThatThrownBy(() -> result.records().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 500})
    void recognizesSoapFaultWithoutExposingUpstreamDetails(int status) throws IOException {
        HrmClient client = clientWithBody(status, envelope("<s:Fault><faultcode>s:Server</faultcode>"
            + "<faultstring>敏感数据 secret-password</faultstring></s:Fault>"));
        assertThatThrownBy(client::getHrmSubcompanyInfo).isInstanceOf(HrmClientException.class)
            .hasMessageContaining("SOAP Fault").hasMessageNotContaining("secret-password");
    }

    @Test
    void reportsHttpErrorsWithoutEchoingResponseBody() throws IOException {
        HrmClient client = clientWithBody(403, "<html>private-data</html>");
        assertThatThrownBy(client::getHrmSubcompanyInfo).isInstanceOf(HrmClientException.class)
            .hasMessageContaining("403").hasMessageNotContaining("private-data");
        assertThat(logOutput()).contains("status=403", "HRM 调用失败").doesNotContain("private-data");
    }

    @ParameterizedTest
    @ValueSource(strings = {"<broken", "<html/>",
        "<h:getHrmSubcompanyInfoResponse xmlns:h='http://localhost/services/HrmService'/>",
        "<h:getHrmDepartmentInfoResponse xmlns:h='http://localhost/services/HrmService'><h:out/></h:getHrmDepartmentInfoResponse>"})
    void rejectsMalformedOrUnexpectedResponses(String body) throws IOException {
        HrmClient client = clientWithBody(200, envelope(body));
        assertThatThrownBy(client::getHrmSubcompanyInfo).isInstanceOf(HrmClientException.class);
    }

    @Test
    void rejectsDoctypeAndExternalEntities() throws IOException {
        HrmClient client = clientWithBody(200, "<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///nonexistent'>]>"
            + envelope("<h:getHrmSubcompanyInfoResponse xmlns:h='" + SERVICE_NS + "'>"
                + "<h:out>&secret;</h:out></h:getHrmSubcompanyInfoResponse>"));
        assertThatThrownBy(client::getHrmSubcompanyInfo).isInstanceOf(HrmClientException.class);
    }

    @Test
    void rejectsUnexpectedRecordTypeInsteadOfReturningEmptySuccess() throws IOException {
        HrmClient client = client("getHrmSubcompanyInfo", 200, "<b:DepartmentBean/>");
        assertThatThrownBy(client::getHrmSubcompanyInfo).isInstanceOf(HrmClientException.class);
    }

    @Test
    void queriesUpstreamWithoutEnableConfiguration() throws IOException {
        HrmQueryResult result = client("getHrmSubcompanyInfo", 200, "").getHrmSubcompanyInfo();

        assertThat(result.nullResult()).isFalse();
        assertThat(result.records()).isEmpty();
        assertThat(bodies).hasSize(1);
    }

    @Test
    void invalidConfigurationFailsBeforeNetworkCall() throws IOException {
        HrmProperties properties = properties();
        properties.setClientIp("999.1.1.1");
        try (HrmClient client = new HrmClient(properties)) {
            assertThatThrownBy(client::getHrmSubcompanyInfo)
                .isInstanceOf(HrmClientException.class).hasMessageContaining("client-ip");
            properties.setClientIp("192.0.2.10");
            properties.setEndpoint("http://localhost/services/HrmService?wsdl");
            assertThatThrownBy(client::getHrmSubcompanyInfo)
                .isInstanceOf(HrmClientException.class).hasMessageContaining("endpoint");
            properties.setEndpoint("http://localhost/services/HrmService");
            properties.setReadTimeoutSeconds(0);
            assertThatThrownBy(client::getHrmSubcompanyInfo)
                .isInstanceOf(HrmClientException.class).hasMessageContaining("超时");
        }
    }

    @Test
    void requestTimesOutInsteadOfWaitingIndefinitely() throws IOException, InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/HrmService", exchange -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        HrmProperties properties = properties();
        properties.setReadTimeoutSeconds(1);
        try {
            HrmClient client = new HrmClient(properties);
            clients.add(client);
            assertThatThrownBy(client::getHrmSubcompanyInfo)
                .isInstanceOf(HrmClientException.class).hasMessageContaining("超时");
        } finally {
            release.countDown();
        }
    }

    @Test
    void timeoutAlsoCoversResponseBodyAfterHeadersHaveArrived() throws IOException {
        CountDownLatch release = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/HrmService", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 1000);
                exchange.getResponseBody().write("<s:Envelope".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                release.await(4, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        HrmProperties properties = properties();
        properties.setReadTimeoutSeconds(1);
        try {
            HrmClient client = new HrmClient(properties);
            clients.add(client);
            assertThatThrownBy(client::getHrmSubcompanyInfo)
                .isInstanceOf(HrmClientException.class).hasMessageContaining("超时");
        } finally {
            release.countDown();
        }
    }

    private HrmClient client(String method, int status, String records) throws IOException {
        return clientWithBody(status, envelope("<h:" + method + "Response xmlns:h=\"" + SERVICE_NS
            + "\"><h:out xmlns:b=\"" + BEAN_NS + "\">" + records + "</h:out></h:"
            + method + "Response>"));
    }

    private HrmClient clientWithBody(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/HrmService", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            actions.add(exchange.getRequestHeaders().getFirst("SOAPAction"));
            contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/xml; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        HrmClient client = new HrmClient(properties());
        clients.add(client);
        return client;
    }

    private boolean responseLogEnabled;

    private HrmProperties properties() throws IOException {
        HrmProperties properties = new HrmProperties();
        properties.setLogResponseBody(responseLogEnabled);
        properties.setClientIp("192.0.2.10");
        properties.setEndpoint("http://127.0.0.1:" + (server == null ? 1 : server.getAddress().getPort())
            + "/services/HrmService");
        return properties;
    }

    private String envelope(String body) {
        return "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
            + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><s:Body>" + body
            + "</s:Body></s:Envelope>";
    }
}
