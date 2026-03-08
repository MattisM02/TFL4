package de.mattis.jvmoptimdemo;

import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

import javax.xml.transform.Source;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.File;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;

/**
 * SchemaFactory-Wrapper fuer GraalVM Native Image.
 *
 * <p>In GraalVM Native Image liefert {@code ClassLoader.getResource()} URLs mit
 * dem internen {@code resource:}-Protokoll. Xerces' XSD-Prozessor kann diese URLs
 * nicht intern oeffnen, wodurch relative {@code <xs:import schemaLocation="..."/>}
 * Referenzen (z.B. {@code xmldsig-core-schema.xsd} aus {@code ebics_types_H004.xsd})
 * fehlschlagen — Fehler: "Cannot resolve the name 'ds:DigestValueType'".</p>
 *
 * <p>Diese Factory delegiert an die Standard-SchemaFactory und setzt automatisch
 * einen {@link LSResourceResolver}, der {@code resource:}-URLs auf
 * {@code ClassLoader.getResourceAsStream()} abbildet.</p>
 *
 * <p>Wird nur im Native Image aktiviert (ueber System Property
 * {@code javax.xml.validation.SchemaFactory:http://www.w3.org/2001/XMLSchema}
 * in der Dockerfile oder per {@code NativeSchemaFactoryRegistrar}).</p>
 */
public class NativeSchemaFactory extends SchemaFactory {

    private final SchemaFactory delegate;

    public NativeSchemaFactory() {
        // newDefaultInstance() liefert die eingebaute System-Default-SchemaFactory
        // (Xerces) ueber die oeffentliche JAXP-API — ohne Reflection auf
        // com.sun.org.apache.xerces.internal.* und ohne --add-opens.
        this.delegate = SchemaFactory.newDefaultInstance();
        this.delegate.setResourceResolver(new ResourceProtocolResolver());
    }

    @Override
    public boolean isSchemaLanguageSupported(String schemaLanguage) {
        return delegate.isSchemaLanguageSupported(schemaLanguage);
    }

    @Override
    public void setErrorHandler(org.xml.sax.ErrorHandler errorHandler) {
        delegate.setErrorHandler(errorHandler);
    }

    @Override
    public org.xml.sax.ErrorHandler getErrorHandler() {
        return delegate.getErrorHandler();
    }

    @Override
    public void setResourceResolver(LSResourceResolver resourceResolver) {
        // Wenn der Caller einen eigenen Resolver setzt, wrappen wir ihn
        // damit unser resource:-Resolver als Fallback dient
        if (resourceResolver instanceof ResourceProtocolResolver) {
            delegate.setResourceResolver(resourceResolver);
        } else {
            delegate.setResourceResolver(new ChainedResolver(resourceResolver));
        }
    }

    @Override
    public LSResourceResolver getResourceResolver() {
        return delegate.getResourceResolver();
    }

    @Override
    public Schema newSchema(Source[] schemas) throws SAXException {
        return delegate.newSchema(schemas);
    }

    @Override
    public Schema newSchema(File schema) throws SAXException {
        return delegate.newSchema(schema);
    }

    @Override
    public Schema newSchema(URL schema) throws SAXException {
        return delegate.newSchema(schema);
    }

    @Override
    public Schema newSchema() throws SAXException {
        return delegate.newSchema();
    }

    // ---- LSResourceResolver fuer resource:-Protokoll ----

    /**
     * Resolver der resource:-URLs auf ClassLoader.getResourceAsStream() abbildet.
     * Xerces ruft diesen Resolver fuer jeden xs:import/xs:include auf.
     *
     * <p>Hinweis zum InputStream-Lifecycle: Der InputStream wird via LSInput an Xerces
     * uebergeben. Xerces uebernimmt die Verantwortung fuer das Schliessen des Streams
     * nach dem Parsen — das ist das Standard-LSInput-Vertragsmuster.</p>
     */
    static class ResourceProtocolResolver implements LSResourceResolver {
        @Override
        public LSInput resolveResource(String type, String namespaceURI,
                String publicId, String systemId, String baseURI) {
            if (systemId == null) return null;

            String resourcePath = resolveResourcePath(systemId, baseURI);
            if (resourcePath == null) return null;

            InputStream is = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(resourcePath);
            if (is == null) {
                is = NativeSchemaFactory.class.getClassLoader()
                        .getResourceAsStream(resourcePath);
            }
            if (is != null) {
                return new SimpleLSInput(is, systemId, baseURI);
            }
            return null;
        }

        private String resolveResourcePath(String systemId, String baseURI) {
            // Absolute resource:-URL
            if (systemId.startsWith("resource:/")) {
                return systemId.substring("resource:/".length());
            }
            // Relative path — resolve against baseURI
            if (!systemId.contains(":") && baseURI != null && baseURI.startsWith("resource:/")) {
                String basePath = baseURI.substring("resource:/".length());
                int lastSlash = basePath.lastIndexOf('/');
                if (lastSlash >= 0) {
                    return basePath.substring(0, lastSlash + 1) + systemId;
                }
                return systemId;
            }
            // Nicht-resource-URL — nicht behandeln
            return null;
        }
    }

    /**
     * Chained Resolver: Versucht zuerst den Caller-Resolver, dann den
     * resource:-Resolver als Fallback.
     */
    static class ChainedResolver implements LSResourceResolver {
        private final LSResourceResolver primary;
        private final ResourceProtocolResolver fallback = new ResourceProtocolResolver();

        ChainedResolver(LSResourceResolver primary) {
            this.primary = primary;
        }

        @Override
        public LSInput resolveResource(String type, String namespaceURI,
                String publicId, String systemId, String baseURI) {
            LSInput result = primary.resolveResource(type, namespaceURI, publicId, systemId, baseURI);
            if (result != null) return result;
            return fallback.resolveResource(type, namespaceURI, publicId, systemId, baseURI);
        }
    }

    /** Minimale LSInput-Implementierung. */
    static class SimpleLSInput implements LSInput {
        private InputStream byteStream;
        private String systemId;
        private String baseURI;

        SimpleLSInput(InputStream byteStream, String systemId, String baseURI) {
            this.byteStream = byteStream;
            this.systemId = systemId;
            this.baseURI = baseURI;
        }

        @Override public Reader getCharacterStream() { return null; }
        @Override public void setCharacterStream(Reader characterStream) {}
        @Override public InputStream getByteStream() { return byteStream; }
        @Override public void setByteStream(InputStream byteStream) { this.byteStream = byteStream; }
        @Override public String getStringData() { return null; }
        @Override public void setStringData(String stringData) {}
        @Override public String getSystemId() { return systemId; }
        @Override public void setSystemId(String systemId) { this.systemId = systemId; }
        @Override public String getPublicId() { return null; }
        @Override public void setPublicId(String publicId) {}
        @Override public String getBaseURI() { return baseURI; }
        @Override public void setBaseURI(String baseURI) { this.baseURI = baseURI; }
        @Override public String getEncoding() { return null; }
        @Override public void setEncoding(String encoding) {}
        @Override public boolean getCertifiedText() { return false; }
        @Override public void setCertifiedText(boolean certifiedText) {}
    }
}
