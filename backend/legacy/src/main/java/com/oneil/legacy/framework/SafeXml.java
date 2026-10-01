package com.oneil.legacy.framework;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.ext.DefaultHandler2;

/** Local, nonvalidating XML only. Legacy external DOCTYPEs never cause I/O. */
public final class SafeXml {
    public static final class Element {
        public final String name;
        public final Map<String, String> attributes;
        public final List<Element> children = new ArrayList<>();
        public final StringBuilder text = new StringBuilder();
        public final int line;
        public final int column;
        Element(String name, Map<String, String> attributes, int line, int column) {
            this.name = name; this.attributes = attributes; this.line = line; this.column = column;
        }
        public String attr(String name) { return attributes.getOrDefault(name, ""); }
        public List<Element> children(String name) { return children.stream().filter(e -> e.name.equals(name)).toList(); }
        public String childText(String name) { return children(name).stream().findFirst().map(e -> e.text.toString().trim()).orElse(""); }
        public List<Element> descendants(String name) {
            List<Element> found = new ArrayList<>();
            for (Element child : children) {
                if (child.name.equals(name)) found.add(child);
                found.addAll(child.descendants(name));
            }
            return found;
        }
    }

    public static Element parse(byte[] bytes) throws Exception {
        var factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        var reader = factory.newSAXParser().getXMLReader();
        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var handler = new DefaultHandler2() {
            final Deque<Element> stack = new ArrayDeque<>();
            Locator locator;
            Element root;
            int count;
            @Override public void setDocumentLocator(Locator locator) { this.locator = locator; }
            @Override public void startElement(String uri, String local, String qualified, Attributes attrs) throws SAXException {
                if (++count > 20000 || stack.size() >= 100) throw new SAXException("XML limit exceeded");
                Map<String, String> values = new HashMap<>();
                for (int i = 0; i < attrs.getLength(); i++) {
                    // Namespaced extension attributes are not ordinary Spring/Struts attributes.
                    if (attrs.getURI(i).isEmpty()) values.put(attrs.getLocalName(i), attrs.getValue(i));
                }
                boolean supported = uri.isEmpty() || Set.of("http://www.springframework.org/schema/beans", "http://www.hibernate.org/xsd/hibernate-mapping",
                        "http://java.sun.com/xml/ns/javaee", "http://java.sun.com/xml/ns/j2ee",
                        "http://xmlns.jcp.org/xml/ns/javaee", "https://jakarta.ee/xml/ns/jakartaee").contains(uri);
                var element = new Element(supported ? local : "unsupported:" + local, values, locator.getLineNumber(), locator.getColumnNumber());
                if (stack.isEmpty()) root = element; else stack.peek().children.add(element);
                stack.push(element);
            }
            @Override public void characters(char[] chars, int start, int length) throws SAXException {
                if (!stack.isEmpty()) {
                    if (stack.peek().text.length() + length > 65536) throw new SAXException("XML text limit exceeded");
                    stack.peek().text.append(chars, start, length);
                }
            }
            @Override public void endElement(String uri, String local, String qualified) { stack.pop(); }
            @Override public void internalEntityDecl(String name, String value) throws SAXException { throw new SAXException("Entity declarations prohibited"); }
            @Override public void externalEntityDecl(String name, String publicId, String systemId) throws SAXException { throw new SAXException("Entity declarations prohibited"); }
            @Override public void skippedEntity(String name) throws SAXException { throw new SAXException("Entity reference prohibited"); }
            @Override public InputSource resolveEntity(String publicId, String systemId) { return new InputSource(new StringReader("")); }
            @Override public void error(SAXParseException error) throws SAXException { throw error; }
            @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
        };
        reader.setContentHandler(handler);
        reader.setErrorHandler(handler);
        reader.setEntityResolver(handler);
        reader.setProperty("http://xml.org/sax/properties/declaration-handler", handler);
        reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
        return handler.root;
    }
}
