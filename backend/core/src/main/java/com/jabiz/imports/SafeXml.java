package com.jabiz.imports;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;

/**
 * StAX readers that resolve nothing: no DTD, no external entity, no entity expansion (XXE and "billion laughs"). A
 * document that declares a DTD is refused outright rather than read without it.
 */
final class SafeXml {

    private static final XMLInputFactory FACTORY = factory();

    private SafeXml() {}

    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> {
            throw new XMLStreamException("External resources are not read");
        });
        return factory;
    }

    static XMLStreamReader open(InputStream in) throws XMLStreamException {
        return FACTORY.createXMLStreamReader(in);
    }

    /** The next event; a DTD or an entity reference refuses the document. */
    static int next(XMLStreamReader reader, String part) throws XMLStreamException {
        int event = reader.next();
        if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE
            || event == XMLStreamConstants.ENTITY_DECLARATION) {
            throw new ImportFileException(part, "Documents with a DTD or entity references are not read");
        }
        return event;
    }
}
