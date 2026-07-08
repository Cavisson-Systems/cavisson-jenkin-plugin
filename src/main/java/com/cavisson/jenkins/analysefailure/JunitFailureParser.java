package com.cavisson.jenkins.analysefailure;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses a Cavisson JUnit test suite report to find failing testcases and derive each one's
 * analysis inputs (scenario/project/subProject/userName/workProfileName/trNumber) directly from
 * the report, with no extra task inputs needed. Testsuite-level {@code <property name="..."/>}
 * entries give userName ("started_by") and workProfileName (second part of "workspace",
 * e.g. "admin/system" -&gt; "system"); each failing {@code <testcase>}'s own "tr_number" property
 * and its {@code name} attribute (e.g. "AI/demo/testcaseName" -&gt; project/subProject/scenario)
 * give the rest.
 */
final class JunitFailureParser {

    private JunitFailureParser() {
    }

    static List<AnalysisTarget> parseFailingTestcases(String junitXml) throws IOException {
        Element testsuite;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(junitXml)));
            testsuite = document.getDocumentElement();
        } catch (ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IOException("Failed to parse JUnit report: " + e.getMessage(), e);
        }

        String userName = readDirectProperty(testsuite, "started_by");
        String workProfileName = secondSegment(readDirectProperty(testsuite, "workspace"), "/");

        List<AnalysisTarget> targets = new ArrayList<>();
        NodeList testcases = testsuite.getElementsByTagName("testcase");

        for (int i = 0; i < testcases.getLength(); i++) {
            Element testcase = (Element) testcases.item(i);
            if (!"failed".equalsIgnoreCase(readDirectProperty(testcase, "status"))) {
                continue;
            }

            String name = testcase.getAttribute("name");
            String trNumber = readDirectProperty(testcase, "tr_number");
            String[] parts = name.split("/");

            String project = parts.length > 0 ? parts[0] : "";
            String subProject = parts.length > 1 ? parts[1] : "";
            String scenario = parts.length > 0 ? parts[parts.length - 1] : name;

            targets.add(new AnalysisTarget(trNumber, scenario, project, subProject, userName, workProfileName));
        }

        return targets;
    }

    private static Element directChild(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tagName.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static String readDirectProperty(Element parent, String propertyName) {
        Element properties = directChild(parent, "properties");
        if (properties == null) {
            return "";
        }

        NodeList propertyNodes = properties.getElementsByTagName("property");
        for (int i = 0; i < propertyNodes.getLength(); i++) {
            Element property = (Element) propertyNodes.item(i);
            if (propertyName.equals(property.getAttribute("name"))) {
                return property.getAttribute("value");
            }
        }
        return "";
    }

    private static String secondSegment(String value, String delimiter) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String[] parts = value.split(Pattern.quote(delimiter));
        return parts.length > 1 ? parts[1] : "";
    }
}
