/*
 * Copyright (c) 2004-2016 MarkLogic Corporation
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

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;


public class TrustAnyoneSSLConfig extends AbstractSSLConfig{
	
	@Override
	public SSLContext getSSLContext() throws NoSuchAlgorithmException,KeyManagementException {
		SSLContext sslContext = SSLContext.getInstance("SSLv3");
		TrustManager[] trust = new TrustManager[] {new TrustAnyoneManager()};
		sslContext.init(null, trust, null);
		return sslContext;
	}
	
	private class TrustAnyoneManager implements X509TrustManager{
		public TrustAnyoneManager(){}
        @Override
		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}

        @Override
		public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {
			// no exception means it's okay
		}

        @Override
		public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
			// no exception means it's okay
		}
	}

	@Override
	public String[] getEnabledCipherSuites() {
		return null;
	}

	@Override
	public String[] getEnabledProtocols() {
		return null;
	}
}
