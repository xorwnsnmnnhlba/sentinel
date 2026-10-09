package com.sentinel.application;

import java.io.StringWriter;
import java.util.List;
import java.util.Locale;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.sentinel.domain.TestResult.Verdict;
import com.sentinel.runner.assertion.AssertionResult;

/**
 * 실행 결과의 JUnit XML. CI 도구(GitHub Actions 테스트 요약, Jenkins, GitLab)가 결과를 표로 보여 주도록
 * <ul>
 * <li>실행 1건 = testsuite 1개 (이름 "스위트 (환경)"), 케이스 = testcase (classname은 스위트 이름)</li>
 * <li>판정 실패 = failure (실패한 규칙), 오류(응답 없음·전송 전 실패) = error (사유), 통과 = 내용 없음</li>
 * <li>요청·응답 원문은 넣지 않음 (CI 로그·산출물은 볼 수 있는 사람이 더 많음). 메시지는 저장 시 이미 시크릿을 가린 값</li>
 * </ul>
 * XML 1.0에 넣을 수 없는 제어 문자는 제거
 */
public final class JUnitReport {

	private static final XMLOutputFactory OUTPUT = XMLOutputFactory.newFactory();

	private JUnitReport() {
	}

	/**
	 * @param runUrl 결과 화면 주소. 없으면 null
	 */
	public static String of(TestRunDetail run, String runUrl) {
		StringWriter out = new StringWriter();
		try {
			XMLStreamWriter xml = OUTPUT.createXMLStreamWriter(out);
			xml.writeStartDocument("UTF-8", "1.0");
			xml.writeCharacters("\n");
			TestRunDetail.Summary summary = run.summary();
			String time = seconds(run.results().stream().mapToLong(JUnitReport::elapsedMs).sum());

			xml.writeStartElement("testsuites");
			xml.writeAttribute("name", "Sentinel");
			counts(xml, summary, time);

			xml.writeStartElement("testsuite");
			xml.writeAttribute("name", safe(run.suite().name() + " (" + run.environment().name() + ")"));
			counts(xml, summary, time);
			xml.writeAttribute("skipped", "0");
			xml.writeAttribute("timestamp", run.startedAt().toString());

			xml.writeStartElement("properties");
			property(xml, "sentinel.run", String.valueOf(run.id()));
			property(xml, "sentinel.status", run.status().name());
			property(xml, "sentinel.environment", safe(run.environment().name()));
			if (runUrl != null) {
				property(xml, "sentinel.result", safe(runUrl));
			}
			xml.writeEndElement();

			for (TestRunDetail.Result result : run.results()) {
				testCase(xml, run.suite().name(), result);
			}
			xml.writeEndElement();
			xml.writeEndElement();
			xml.writeEndDocument();
			xml.close();
		}
		catch (XMLStreamException ex) {
			throw new IllegalStateException("cannot write JUnit report of run " + run.id(), ex);
		}
		return out + "\n";
	}

	private static void testCase(XMLStreamWriter xml, String suiteName, TestRunDetail.Result result)
			throws XMLStreamException {
		xml.writeStartElement("testcase");
		xml.writeAttribute("name", safe(result.testCase().name()));
		xml.writeAttribute("classname", safe(suiteName));
		xml.writeAttribute("time", seconds(elapsedMs(result)));
		if (result.verdict() == Verdict.FAILED) {
			List<String> failed = result.assertionResults()
				.stream()
				.filter(assertion -> !assertion.passed())
				.map(JUnitReport::describe)
				.toList();
			xml.writeStartElement("failure");
			xml.writeAttribute("type", "assertion");
			xml.writeAttribute("message", safe(failed.isEmpty() ? "failed" : failed.getFirst()));
			xml.writeCharacters(safe(String.join("\n", failed)));
			xml.writeEndElement();
		}
		else if (result.verdict() == Verdict.ERROR) {
			String reason = result.errorReason() == null ? "ERROR" : result.errorReason().name();
			String message = result.errorMessage() == null ? reason : result.errorMessage();
			if (result.retries() > 0) {
				// 일시적인 흔들림이 아니라 다시 보내도 응답이 없었음
				message += " (" + result.retries() + "회 다시 보낸 뒤)";
			}
			xml.writeStartElement("error");
			xml.writeAttribute("type", reason);
			xml.writeAttribute("message", safe(message));
			xml.writeCharacters(safe(message));
			xml.writeEndElement();
		}
		xml.writeEndElement();
	}

	private static void counts(XMLStreamWriter xml, TestRunDetail.Summary summary, String time)
			throws XMLStreamException {
		xml.writeAttribute("tests", String.valueOf(summary.total()));
		xml.writeAttribute("failures", String.valueOf(summary.failed()));
		xml.writeAttribute("errors", String.valueOf(summary.errors()));
		xml.writeAttribute("time", time);
	}

	private static void property(XMLStreamWriter xml, String name, String value) throws XMLStreamException {
		xml.writeEmptyElement("property");
		xml.writeAttribute("name", name);
		xml.writeAttribute("value", value);
	}

	private static String describe(AssertionResult assertion) {
		return assertion.message() == null ? assertion.description()
				: assertion.description() + ": " + assertion.message();
	}

	private static long elapsedMs(TestRunDetail.Result result) {
		return result.elapsedMs() == null ? 0 : result.elapsedMs();
	}

	private static String seconds(long millis) {
		return String.format(Locale.ROOT, "%.3f", millis / 1000.0);
	}

	/** XML 1.0에서 허용하지 않는 문자(제어 문자 등) 제거. 응답에서 온 판정 메시지에 섞일 수 있음 */
	static String safe(String text) {
		if (text == null) {
			return "";
		}
		return text.codePoints()
			.filter(JUnitReport::allowed)
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
			.toString();
	}

	private static boolean allowed(int codePoint) {
		return codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD || (codePoint >= 0x20 && codePoint <= 0xD7FF)
				|| (codePoint >= 0xE000 && codePoint <= 0xFFFD) || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
	}

}
