package com.sentinel.application;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestRun;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.assertion.AssertionResult;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import static org.assertj.core.api.Assertions.assertThat;

class JUnitReportTest {

	private static final Instant STARTED = Instant.parse("2026-10-01T01:00:00Z");

	@Test
	void writesOneSuiteWithACaseForEachResult() throws Exception {
		TestRunDetail run = run(List.of(passed("주문 조회", 120),
				failed("결제 승인", 340, new AssertionResult("status in [2xx]", false, "was 500"),
						new AssertionResult("response time <= 300ms", false, "was 340ms"),
						new AssertionResult("header Content-Type", true, "application/json")),
				error("환불", TestResult.ErrorReason.TIMEOUT, "TIMEOUT: GET https://gw/refunds", 2)));

		Document xml = parse(JUnitReport.of(run, "https://sentinel.example.com/runs/12"));

		Element suites = xml.getDocumentElement();
		assertThat(suites.getTagName()).isEqualTo("testsuites");
		assertThat(suites.getAttribute("tests")).isEqualTo("3");
		assertThat(suites.getAttribute("failures")).isEqualTo("1");
		assertThat(suites.getAttribute("errors")).isEqualTo("1");
		Element suite = (Element) xml.getElementsByTagName("testsuite").item(0);
		assertThat(suite.getAttribute("name")).isEqualTo("결제 (dev)");
		assertThat(suite.getAttribute("time")).isEqualTo("0.460");
		assertThat(suite.getAttribute("timestamp")).isEqualTo("2026-10-01T01:00:00Z");
		assertThat(properties(xml)).containsExactly("sentinel.run=12", "sentinel.status=COMPLETED",
				"sentinel.environment=dev", "sentinel.result=https://sentinel.example.com/runs/12");

		Element pass = testCase(xml, 0);
		assertThat(pass.getAttribute("name")).isEqualTo("주문 조회");
		assertThat(pass.getAttribute("classname")).isEqualTo("결제");
		assertThat(pass.getAttribute("time")).isEqualTo("0.120");
		assertThat(pass.getChildNodes().getLength()).isZero();

		// 실패한 규칙만, 첫 번째를 요약 메시지로
		Element failure = (Element) testCase(xml, 1).getElementsByTagName("failure").item(0);
		assertThat(failure.getAttribute("type")).isEqualTo("assertion");
		assertThat(failure.getAttribute("message")).isEqualTo("status in [2xx]: was 500");
		assertThat(failure.getTextContent())
			.isEqualTo("status in [2xx]: was 500\nresponse time <= 300ms: was 340ms");

		Element error = (Element) testCase(xml, 2).getElementsByTagName("error").item(0);
		assertThat(error.getAttribute("type")).isEqualTo("TIMEOUT");
		assertThat(error.getAttribute("message")).isEqualTo("TIMEOUT: GET https://gw/refunds (2회 다시 보낸 뒤)");
		assertThat(testCase(xml, 2).getAttribute("time")).isEqualTo("0.000");
	}

	@Test
	void leavesOutRawPayloadsAndCharactersXmlCannotHold() throws Exception {
		TestRunDetail run = run(List.of(failed("a < b & \"c\"", 1,
				new AssertionResult("body contains 'ok'", false, "was \u0001\u0007bad\u001B[0m <tag>"))));

		String report = JUnitReport.of(run, null);
		Document xml = parse(report);

		assertThat(testCase(xml, 0).getAttribute("name")).isEqualTo("a < b & \"c\"");
		assertThat(xml.getElementsByTagName("failure").item(0).getTextContent())
			.isEqualTo("body contains 'ok': was bad[0m <tag>");
		assertThat(report).doesNotContain("request-body-secret").doesNotContain("response-body-secret");
		assertThat(properties(xml)).doesNotContain("sentinel.result=");
	}

	private static TestRunDetail run(List<TestRunDetail.Result> results) {
		int passed = (int) results.stream().filter(r -> r.verdict() == TestResult.Verdict.PASSED).count();
		int failed = (int) results.stream().filter(r -> r.verdict() == TestResult.Verdict.FAILED).count();
		int errors = (int) results.stream().filter(r -> r.verdict() == TestResult.Verdict.ERROR).count();
		return new TestRunDetail(12, new IdName(1, "billing"), new IdName(3, "결제"), new IdName(2, "dev"),
				TestRun.Trigger.SCHEDULED, TestRun.Status.COMPLETED, STARTED,
				STARTED.plusSeconds(1), new TestRunDetail.Summary(results.size(), passed, failed, errors), results,
				null, null, null, null, null, results.size(), null, null);
	}

	private static TestRunDetail.Result passed(String name, long elapsedMs) {
		return result(name, TestResult.Verdict.PASSED, List.of(), null, null, elapsedMs, 0);
	}

	private static TestRunDetail.Result failed(String name, long elapsedMs, AssertionResult... assertions) {
		return result(name, TestResult.Verdict.FAILED, List.of(assertions), null, null, elapsedMs, 0);
	}

	private static TestRunDetail.Result error(String name, TestResult.ErrorReason reason, String message, int retries) {
		return result(name, TestResult.Verdict.ERROR, List.of(), reason, message, null, retries);
	}

	private static TestRunDetail.Result result(String name, TestResult.Verdict verdict,
			List<AssertionResult> assertions, TestResult.ErrorReason reason, String message, Long elapsedMs,
			int retries) {
		return new TestRunDetail.Result(1, new IdName(1, name), verdict, TestResult.Outcome.RESPONDED, assertions, reason, message,
				elapsedMs,
				new TestRunDetail.Request(HttpMethod.POST, "https://gw/x", Map.of(), "request-body-secret"),
				new TestRunDetail.Response(200, Map.of(), "response-body-secret", false), null, null, null, retries,
				false);
	}

	private static Document parse(String xml) throws Exception {
		return DocumentBuilderFactory.newInstance()
			.newDocumentBuilder()
			.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
	}

	private static Element testCase(Document xml, int index) {
		return (Element) xml.getElementsByTagName("testcase").item(index);
	}

	private static List<String> properties(Document xml) {
		var nodes = xml.getElementsByTagName("property");
		return java.util.stream.IntStream.range(0, nodes.getLength())
			.mapToObj(i -> (Element) nodes.item(i))
			.map(element -> element.getAttribute("name") + "=" + element.getAttribute("value"))
			.toList();
	}

}
