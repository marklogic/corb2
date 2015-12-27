/*
 * Copyright (c) 2004-2015 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * The use of the Apache License does not indicate that this project is
 * affiliated with the Apache Software Foundation.
 */
package com.marklogic.developer.corb;

import static com.marklogic.developer.corb.Options.MAX_OPTS_FROM_MODULE;
import static com.marklogic.developer.corb.Options.POST_BATCH_MODULE;
import static com.marklogic.developer.corb.Options.PRE_BATCH_MODULE;
import static com.marklogic.developer.corb.Options.PROCESS_MODULE;
import static com.marklogic.developer.corb.Options.URIS_MODULE;
import static com.marklogic.developer.corb.Options.URIS_REPLACE_PATTERN;
import static com.marklogic.developer.corb.Options.XQUERY_MODULE;
import static com.marklogic.developer.corb.util.StringUtils.buildModulePath;
import static com.marklogic.developer.corb.util.StringUtils.isAdhoc;
import static com.marklogic.developer.corb.util.StringUtils.isJavaScriptModule;
import static com.marklogic.developer.corb.util.StringUtils.isNotEmpty;
import static com.marklogic.developer.corb.util.StringUtils.trim;
import com.marklogic.xcc.ContentSource;
import com.marklogic.xcc.Request;
import com.marklogic.xcc.RequestOptions;
import com.marklogic.xcc.ResultItem;
import com.marklogic.xcc.ResultSequence;
import com.marklogic.xcc.Session;
import com.marklogic.xcc.exceptions.RequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import static com.marklogic.developer.corb.util.StringUtils.buildModulePath;

public class QueryUrisLoader implements UrisLoader {
	private static final int DEFAULT_MAX_OPTS_FROM_MODULE = 10;
	private static final Pattern MODULE_CUSTOM_INPUT = Pattern.compile("(" + 
            PRE_BATCH_MODULE + "|" + PROCESS_MODULE + "|" + XQUERY_MODULE + "|" + POST_BATCH_MODULE +
            ")\\.[A-Za-z0-9]+=[A-Za-z0-9]+");
	
	TransformOptions options;
	ContentSource cs;
	String collection;
	Properties properties;

	Session session;
	ResultSequence res;

	String batchRef;
	int total = 0;

	String[] replacements = new String[0];

	private static final Logger LOG = Logger.getLogger(QueryUrisLoader.class.getName());

	public QueryUrisLoader() {
	}

	@Override
	public void setOptions(TransformOptions options) {
		this.options = options;
	}

	@Override
	public void setContentSource(ContentSource cs) {
		this.cs = cs;
	}

	@Override
	public void setCollection(String collection) {
		this.collection = collection;
	}

	@Override
	public void setProperties(Properties properties) {
		this.properties = properties;
	}

	@Override
	public void open() throws CorbException {
		List<String> propertyNames = new ArrayList<String>();
        if (properties != null) {
            propertyNames.addAll(properties.stringPropertyNames());
        }
		propertyNames.addAll(System.getProperties().stringPropertyNames());

		if (propertyNames.contains(URIS_REPLACE_PATTERN)) {
			String urisReplacePattern = getProperty(URIS_REPLACE_PATTERN);
			if (urisReplacePattern != null && urisReplacePattern.length() > 0) {
				replacements = urisReplacePattern.split(",", -1);
				if (replacements.length % 2 != 0) {
					throw new IllegalArgumentException("Invalid replacement pattern " + urisReplacePattern);
				}
			}
		}

		try {
			RequestOptions opts = new RequestOptions();
			opts.setCacheResult(false);
			// this should be a noop, but xqsync does it
			opts.setResultBufferSize(0);
			LOG.log(Level.INFO, "buffer size = {0}, caching = {1}",
					new Object[] { opts.getResultBufferSize(), opts.getCacheResult() });

			session = cs.newSession();
			Request req = null;
			String urisModule = options.getUrisModule();
			if (isAdhoc(urisModule)) {
				String queryPath = urisModule.substring(0, urisModule.indexOf('|'));
				String adhocQuery = AbstractManager.getAdhocQuery(queryPath);
				if (adhocQuery == null || (adhocQuery.length() == 0)) {
					throw new IllegalStateException("Unable to read adhoc query " + queryPath + " from classpath or filesystem");
				}
				LOG.log(Level.INFO, "invoking adhoc uris module {0}", queryPath);
				req = session.newAdhocQuery(adhocQuery);
				if (isJavaScriptModule(queryPath)) {
					opts.setQueryLanguage("javascript");
				}
			} else {
				String root = options.getModuleRoot();
				String modulePath = buildModulePath(root, urisModule);
				LOG.log(Level.INFO, "invoking uris module {0}", modulePath);
				req = session.newModuleInvoke(modulePath);
			}
			// NOTE: collection will be treated as a CWSV
			req.setNewStringVariable("URIS", collection);
			// TODO support DIRECTORY as type
			req.setNewStringVariable("TYPE", TransformOptions.COLLECTION_TYPE);
			req.setNewStringVariable("PATTERN", "[,\\s]+");

			// custom inputs
			for (String propName : propertyNames) {
				if (propName.startsWith(URIS_MODULE + ".")) {
					String varName = propName.substring((URIS_MODULE + ".").length());
					String value = getProperty(propName);
					if (value != null) {
						req.setNewStringVariable(varName, value);
					}
				}
			}

			req.setOptions(opts);

			res = session.submitRequest(req);
			ResultItem next = res.next();
			
			int maxOpts = this.getMaxOptionsFromModule();
			for (int i=0; i < maxOpts && next != null && batchRef == null && !(next.getItem().asString().matches("\\d+")); i++){
				String value = next.getItem().asString();
				if (MODULE_CUSTOM_INPUT.matcher(value).matches()) {
					int idx = value.indexOf('=');
					properties.put(value.substring(0, idx).replace(XQUERY_MODULE + ".", PROCESS_MODULE + "."), value.substring(idx+1));
				} else {
					batchRef = value;
				}
				next = res.next();
			}
			
			try {
				total = Integer.parseInt(next.getItem().asString());
			} catch(NumberFormatException exc) {
				throw new CorbException("Uris module " + options.getUrisModule() + " does not return total URI count");
			}
		} catch (RequestException exc) {
			throw new CorbException("While invoking Uris Module", exc);
		}
	}

	@Override
	public String getBatchRef() {
		return this.batchRef;
	}

	@Override
	public int getTotalCount() {
		return this.total;
	}

	@Override
	public boolean hasNext() throws CorbException {
		return res != null && res.hasNext();
	}

	@Override
	public String next() throws CorbException {
		String next = res.next().asString();
		for (int i = 0; i < replacements.length - 1; i += 2) {
			next = next.replaceAll(replacements[i], replacements[i + 1]);
		}
		return next;
	}

	@Override
	public void close() {
		if (session != null) {
			LOG.info("closing uris session");
			try {
				if (res != null) {
					res.close();
					res = null;
				}
			} finally {
				session.close();
				session = null;
			}
		}
		cleanup();
	}

	protected void cleanup() {
		// release
		options = null;
		cs = null;
		collection = null;
		properties = null;
		batchRef = null;
		replacements = null;
	}

	public String getProperty(String key) {
		String val = System.getProperty(key);
		if (val == null && properties != null) {
			val = properties.getProperty(key);
		}
		return trim(val);
	}
	
	private int getMaxOptionsFromModule(){
		int max = DEFAULT_MAX_OPTS_FROM_MODULE;
		try {
			String maxStr = getProperty(MAX_OPTS_FROM_MODULE);
			if (isNotEmpty(maxStr)) {
				max = Integer.parseInt(maxStr);
			}
		} catch(Exception exc){}
		return max;
	}
}
