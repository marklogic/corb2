package com.marklogic.developer.corb;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.marklogic.developer.SimpleLogger;
import com.marklogic.xcc.ContentSource;
import com.marklogic.xcc.Request;
import com.marklogic.xcc.ResultSequence;
import com.marklogic.xcc.Session;
import com.marklogic.xcc.exceptions.ServerConnectionException;
import com.marklogic.xcc.types.XdmBinary;
import com.marklogic.xcc.types.XdmItem;

/**
 *
 * @author Bhagat Bandlamudi, MarkLogic Corporation
 *
 */
public abstract class AbstractTask implements Task {

    protected static final String TRUE = "true";
    protected static final String FALSE = "false";
    protected static final byte[] NEWLINE = "\n".getBytes();
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    protected ContentSource cs;
    protected String moduleType;
    protected String moduleUri;
    protected Properties properties;
    protected String[] inputUris;

    protected String adhocQuery;
    protected String language;

    static private Object sync = new Object();
    static private Map<String, Set<String>> modulePropsMap = new HashMap<>();

    protected int DEFAULT_RETRY_LIMIT = 3;
    protected int DEFAULT_RETRY_INTERVAL = 60;

    private int connectRetryCount = 0;

    protected static final SimpleLogger logger;

    static {
        logger = SimpleLogger.getSimpleLogger();
        Properties props = new Properties();
        props.setProperty("LOG_LEVEL", "INFO");
        props.setProperty("LOG_HANDLER", "CONSOLE");
        logger.configureLogger(props);
    }

    public void setContentSource(ContentSource cs) {
        this.cs = cs;
    }

    public void setModuleType(String moduleType) {
        this.moduleType = moduleType;
    }

    public void setModuleURI(String moduleUri) {
        this.moduleUri = moduleUri;
    }

    public void setAdhocQuery(String adhocQuery) {
        this.adhocQuery = adhocQuery;
    }

    public void setQueryLanguage(String language) {
        this.language = language;
    }

    public void setProperties(Properties properties) {
        this.properties = properties;
    }

    public void setInputURI(String[] inputUri) {
        this.inputUris = inputUri;
    }

    public Session newSession() {
        return cs.newSession();
    }

    protected String[] invokeModule() throws CorbException {
        if (moduleUri == null && adhocQuery == null) {
            return null;
        }

        Session session = null;
        ResultSequence seq = null;
        Thread.yield();// try to avoid thread starvation
        try {
            session = newSession();
            Request request = null;

            Set<String> modulePropNames = modulePropsMap.get(moduleType);
            if (modulePropNames == null) {
                synchronized (sync) {
                    modulePropNames = modulePropsMap.get(moduleType);
                    if (modulePropNames == null) {
                        HashSet<String> propSet = new HashSet<>();
                        if (properties != null) {
                            for (String propName : properties.stringPropertyNames()) {
                                if (propName.startsWith(moduleType + ".")) {
                                    propSet.add(propName);
                                }
                            }
                        }
                        for (String propName : System.getProperties().stringPropertyNames()) {
                            if (propName.startsWith(moduleType + ".")) {
                                propSet.add(propName);
                            }
                        }
                        modulePropsMap.put(moduleType, modulePropNames = propSet);
                    }
                }
            }

            if (moduleUri != null) {
                request = session.newModuleInvoke(moduleUri);
            } else {
                request = session.newAdhocQuery(adhocQuery);
            }

            if (language != null) {
                request.getOptions().setQueryLanguage(language);
            }

            if (inputUris != null && inputUris.length > 0) {
                if (inputUris.length == 1) {
                    request.setNewStringVariable("URI", inputUris[0]);
                } else {
                    String delim = getProperty("BATCH-URI-DELIM");
                    if (delim == null || delim.length() == 0) {
                        delim = Manager.DEFAULT_BATCH_URI_DELIM;
                    }
                    StringBuffer buff = new StringBuffer();
                    for (String uri : inputUris) {
                        if (buff.length() > 0) {
                            buff.append(delim);
                        }
                        buff.append(uri);
                    }
                    request.setNewStringVariable("URI", buff.toString());
                }
            }

            if (properties != null && properties.containsKey(Manager.URIS_BATCH_REF)) {
                request.setNewStringVariable(Manager.URIS_BATCH_REF, properties.getProperty(Manager.URIS_BATCH_REF));
            }

            for (String propName : modulePropNames) {
                if (propName.startsWith(moduleType + ".")) {
                    String varName = propName.substring(moduleType.length() + 1);
                    String value = getProperty(propName);
                    if (value != null) {
                        request.setNewStringVariable(varName, value);
                    }
                }
            }
            Thread.yield();// try to avoid thread starvation
            seq = session.submitRequest(request);
            connectRetryCount = 0;
            // no need to hold on to the session as results will be cached.
            session.close();
            Thread.yield();// try to avoid thread starvation

            processResult(seq);
            seq.close();
            Thread.yield();// try to avoid thread starvation

            return inputUris;
        } catch (Exception exc) {
            if (exc instanceof ServerConnectionException) {
                int retryLimit = this.getConnectRetryLimit();
                int retryInterval = this.getConnectRetryInterval();
                if (connectRetryCount < retryLimit) {
                    connectRetryCount++;
                    logger.severe("Connection failed to Marklogic Server. Retrying attempt " + connectRetryCount + " after " + retryInterval + " seconds..: " + exc.getMessage() + " at URI: " + inputUris);
                    try {
                        Thread.sleep(retryInterval * 1000L);
                    } catch (Exception exc2) {
                    }
                    return invokeModule();
                } else {
                    throw new CorbException(exc.getMessage() + " at URI: " + Arrays.asList(inputUris), exc);
                }
            } else {
                throw new CorbException(exc.getMessage() + " at URI: " + Arrays.asList(inputUris), exc);
            }
        } finally {
            if (null != session && !session.isClosed()) {
                session.close();
                session = null;
            }
            if (null != seq && !seq.isClosed()) {
                seq.close();
                seq = null;
            }
            Thread.yield();// try to avoid thread starvation
        }
    }

    protected abstract String processResult(ResultSequence seq) throws CorbException;

    protected void cleanup() {
        //release resources
        cs = null;
        moduleType = null;
        moduleUri = null;
        properties = null;
        inputUris = null;
        adhocQuery = null;
    }

    public String getProperty(String key) {
        String val = System.getProperty(key);
        if (val == null && properties != null) {
            val = properties.getProperty(key);
        }
        return val != null ? val.trim() : null;
    }

    protected byte[] getValueAsBytes(XdmItem item) {
        if (item instanceof XdmBinary) {
            return ((XdmBinary) item).asBinaryData();
        } else if (item != null) {
            return item.asString().getBytes();
        } else {
            return EMPTY_BYTE_ARRAY;
        }
    }

    private int getConnectRetryLimit() {
        int connectRetryLimit = -1;
        String propStr = getProperty("XCC-CONNECTION-RETRY-LIMIT");
        if (propStr != null && propStr.length() > 0) {
            try {
                connectRetryLimit = Integer.parseInt(propStr);
            } catch (Exception exc) {
            }
        }
        return connectRetryLimit < 0 ? DEFAULT_RETRY_LIMIT : connectRetryLimit;
    }

    private int getConnectRetryInterval() {
        int connectRetryInterval = -1;
        String propStr = getProperty("XCC-CONNECTION-RETRY-INTERVAL");
        if (propStr != null && propStr.length() > 0) {
            try {
                connectRetryInterval = Integer.parseInt(propStr);
            } catch (Exception exc) {
            }
        }
        return connectRetryInterval < 0 ? DEFAULT_RETRY_INTERVAL : connectRetryInterval;
    }

}
