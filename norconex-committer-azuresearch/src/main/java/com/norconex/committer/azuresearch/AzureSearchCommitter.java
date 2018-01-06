/* Copyright 2017 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.norconex.committer.azuresearch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.WinHttpClients;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.norconex.committer.core.AbstractCommitter;
import com.norconex.committer.core.AbstractMappedCommitter;
import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.IAddOperation;
import com.norconex.committer.core.ICommitOperation;
import com.norconex.committer.core.IDeleteOperation;
import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.commons.lang.net.ProxySettings;
import com.norconex.commons.lang.time.DurationParser;
import com.norconex.commons.lang.xml.EnhancedXMLStreamWriter;

/**
 * <p>
 * Commits documents to Microsoft Azure Search.
 * </p>
 * 
 * <h3>Document reference encoding</h3>
 * <p>
 * By default the document reference (Azure Search Document Key) is
 * encoded using URL-safe Base64 encoding. This is Azure Search recommended
 * approach when a document unique id can contain special characters
 * (e.g. a URL).  If you know your document references to be safe
 * (e.g. a sequence number), you can 
 * set {@link #setDisableReferenceEncoding(boolean)} to <code>true</code>.
 * To otherwise store a reference value un-encoded, you can additionally 
 * store it in a field other than your reference ("id") field.
 * </p>  
 * 
 * <h3>Field names and errors</h3>
 * <p>
 * Azure Search will produce an error if any of the documents in a submitted 
 * batch contains one or more fields with invalid characters.  To prevent
 * sending those in vain, the committer will validate your fields
 * and throw an exception upon encountering an invalid one.
 * To prevent exceptions from being thrown, you can set 
 * {@link #setIgnoreValidationErrors(boolean)} to <code>true</code> to
 * log those errors instead.
 * </p>
 * <p>
 * An exception will also be thrown for errors returned by Azure Search 
 * (e.g. a field is not defined in your
 * Azure Search schema). To also log those errors instead of throwing an
 * exception, you can set {@link #setIgnoreResponseErrors(boolean)}
 * to <code>true</code>. 
 * </p>
 * <h4>Field naming rules</h4>
 * <p>
 * Those are the field naming rules mandated for Azure Search (in force
 * for Azure Search version 2016-09-01): 
 * Search version  
 * </p>
 * <ul>
 *   <li><b>Document reference (ID):</b> Letters, numbers, dashes ("-"), 
 *       underscores ("_"), and equal signs ("="). First character cannot be
 *       an underscore.</li>
 *   <li><b>Document field name:</b> Letters, numbers, underscores ("_"). First
 *       character must be a letter. Cannot start with "azureSearch". 
 *       Maximum length is 128 characters.</li>
 * </ul>
 * 
 * <h3>Password encryption in XML configuration:</h3>
 * <p>
 * The <code>proxyPassword</code> can take a password that has been
 * encrypted using {@link EncryptionUtil} (or command-line encrypt.[bat|sh]).
 * In order for the password to be decrypted properly by the crawler, you need
 * to specify the encryption key used to encrypt it. The key can be stored
 * in a few supported locations and a combination of
 * <code>proxyPasswordKey</code>
 * and <code>proxyPasswordKeySource</code> must be specified to properly
 * locate the key. The supported sources are:
 * </p>
 * <table border="1" summary="">
 *   <tr>
 *     <th><code>proxyPasswordKeySource</code></th>
 *     <th><code>proxyPasswordKey</code></th>
 *   </tr>
 *   <tr>
 *     <td><code>key</code></td>
 *     <td>The actual encryption key.</td>
 *   </tr>
 *   <tr>
 *     <td><code>file</code></td>
 *     <td>Path to a file containing the encryption key.</td>
 *   </tr>
 *   <tr>
 *     <td><code>environment</code></td>
 *     <td>Name of an environment variable containing the key.</td>
 *   </tr>
 *   <tr>
 *     <td><code>property</code></td>
 *     <td>Name of a JVM system property containing the key.</td>
 *   </tr>
 * </table>
 * 
 * <h3>XML configuration usage:</h3>
 * <pre>
 *  &lt;committer class="com.norconex.committer.azuresearch.AzureSearchCommitter"&gt;
 *      &lt;endpoint&gt;(Azure Search endpoint)&lt;/endpoint&gt;
 *      &lt;apiVersion&gt;(Optional Azure Search API version to use)&lt;/apiVersion&gt;
 *      &lt;apiKey&gt;(Azure Search API admin key)&lt;/apiKey&gt;
 *      &lt;indexName&gt;(Name of the index to use)&lt;/indexName&gt;
 *      &lt;disableReferenceEncoding&gt;[false|true]&lt;/disableReferenceEncoding&gt;
 *      &lt;ignoreValidationErrors&gt;[false|true]&lt;/ignoreValidationErrors&gt;
 *      &lt;ignoreResponseErrors&gt;[false|true]&lt;/ignoreResponseErrors&gt;
 *      &lt;useWindowsAuth&gt;[false|true]&lt;/useWindowsAuth&gt;
 *
 *      &lt;proxyHost&gt;...&lt;/proxyHost&gt;
 *      &lt;proxyPort&gt;...&lt;/proxyPort&gt;
 *      &lt;proxyRealm&gt;...&lt;/proxyRealm&gt;
 *      &lt;proxyScheme&gt;...&lt;/proxyScheme&gt;
 *      &lt;proxyUsername&gt;...&lt;/proxyUsername&gt;
 *      &lt;proxyPassword&gt;...&lt;/proxyPassword&gt;
 *      &lt;!-- Use the following if password is encrypted. --&gt;
 *      &lt;proxyPasswordKey&gt;(the encryption key or a reference to it)&lt;/proxyPasswordKey&gt;
 *      &lt;proxyPasswordKeySource&gt;[key|file|environment|property]&lt;/proxyPasswordKeySource&gt;
 *
 *      &lt;sourceReferenceField keep="[false|true]"&gt;
 *         (Optional name of field that contains the document reference, when 
 *         the default document reference is not used.  The reference value
 *         will be mapped to the Azure Search ID field. 
 *         Once re-mapped, this metadata source field is 
 *         deleted, unless "keep" is set to <code>true</code>.)
 *      &lt;/sourceReferenceField&gt;
 *      &lt;targetReferenceField&gt;
 *         (Name of Azure Search target field where the store a document unique 
 *         identifier (sourceReferenceField).  If not specified, 
 *         default is "id".) 
 *      &lt;/targetReferenceField&gt;
 *      &lt;sourceContentField keep="[false|true]"&gt;
 *         (If you wish to use a metadata field to act as the document 
 *         "content", you can specify that field here.  Default 
 *         does not take a metadata field but rather the document content.
 *         Once re-mapped, the metadata source field is deleted,
 *         unless "keep" is set to <code>true</code>.)
 *      &lt;/sourceContentField&gt;
 *      &lt;targetContentField&gt;
 *         (Target repository field name for a document content/body.
 *          Default is "content".)
 *      &lt;/targetContentField&gt;
 *      &lt;commitBatchSize&gt;
 *          (Max number of documents to send to Azure Search at once.
 *           Maximum is 1000.)
 *      &lt;/commitBatchSize&gt;
 *      &lt;queueDir&gt;(optional path where to queue files)&lt;/queueDir&gt;
 *      &lt;queueSize&gt;(max queue size before committing)&lt;/queueSize&gt;
 *      &lt;maxRetries&gt;(max retries upon commit failures)&lt;/maxRetries&gt;
 *      &lt;maxRetryWait&gt;(max delay in milliseconds between retries)&lt;/maxRetryWait&gt;
 *  &lt;/committer&gt;
 * </pre>
 * <p>
 * XML configuration entries expecting millisecond durations
 * can be provided in human-readable format (English only), as per 
 * {@link DurationParser} (e.g., "5 minutes and 30 seconds" or "5m30s").
 * </p>
 * 
 * <h4>Usage example:</h4>
 * <p>
 * The following example uses the minimum required settings:.  
 * </p> 
 * <pre>
 *  &lt;committer class="com.norconex.committer.azuresearch.AzureSearchCommitter"&gt;
 *      &lt;endpoint&gt;https://example.search.windows.net&lt;/endpoint&gt;
 *      &lt;apiKey&gt;1234567890ABCDEF1234567890ABCDEF&lt;/apiKey&gt;
 *      &lt;indexName&gt;sample-index&lt;/indexName&gt;
 *  &lt;/committer&gt;
 * </pre>
 *  
 * @author Pascal Essiembre
 */
public class AzureSearchCommitter extends AbstractMappedCommitter {

    private static final Logger LOG = 
            LogManager.getLogger(AzureSearchCommitter.class);

    /** Default Azure Search API version */
    public static final String DEFAULT_API_VERSION = "2016-09-01"; 
    /** Default Azure Search document key field */
    public static final String DEFAULT_AZURE_ID_FIELD = "id";
    /** Default Azure Search content field */
    public static final String DEFAULT_AZURE_CONTENT_FIELD = "content";

    private String endpoint;
    private String apiVersion = DEFAULT_API_VERSION;
    private String apiKey;
    private String indexName;
    private boolean disableReferenceEncoding;
    private boolean ignoreValidationErrors;
    private boolean ignoreResponseErrors;
    private final ProxySettings proxySettings = new ProxySettings();
    
    private CloseableHttpClient client;
    private String restURL;
    private boolean useWindowsAuth;
    
    /**
     * Constructor.
     */
    public AzureSearchCommitter() {
        super();
        setTargetReferenceField(DEFAULT_AZURE_ID_FIELD);
        setTargetContentField(DEFAULT_AZURE_CONTENT_FIELD);
    }
    
	/**
     * Gets the index name.
     * @return index name
     */
    public String getIndexName() {
        return indexName;
    }
    /**
     * Sets the index name.
     * @param indexName the index name
     */
    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    /**
     * Gets the Azure Search endpoint 
     * (https://[service name].search.windows.net). 
     * @return Azure Search endpoint
     */
    public String getEndpoint() {
        return endpoint;
    }
    /**
     * Sets the Azure Search endpoint
     * (https://[service name].search.windows.net).
     * @param endpoint Azure Search endpoint
     */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * Gets the Azure API version. Default is {@link #DEFAULT_API_VERSION}.
     * @return the Azure API version
     */
    public String getApiVersion() {
        return apiVersion;
    }
    /**
     * Sets the Azure API version.
     * @param apiVersion Azure API version
     */
    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    /**
     * Gets the Azure API admin key.  
     * @return Azure API admin key
     */
    public String getApiKey() {
        return apiKey;
    }
    /**
     * Sets the Azure API admin key.
     * @param apiKey Azure API admin key
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
    
    /**
     * Whether to disable document reference encoding. By default, references
     * are encoded using a URL-safe Base64 encoding.  When <code>true</code>,
     * document references will be sent as is if they pass validation.
     * @return <code>true</code> if disabling reference encoding
     */
    public boolean isDisableReferenceEncoding() {
        return disableReferenceEncoding;
    }
    /**
     * Sets whether to disable document reference encoding. When 
     * <code>false</code>, references are encoded using a URL-safe Base64 
     * encoding.  When <code>true</code>, document references will be sent as 
     * is if they pass validation.
     * @param disableReferenceEncoding <code>true</code> if disabling 
     *        reference encoding
     */
    public void setDisableReferenceEncoding(boolean disableReferenceEncoding) {
        this.disableReferenceEncoding = disableReferenceEncoding;
    }

    /**
     * Whether to ignore validation errors.  By default, an exception is 
     * thrown if a document contains a field that Azure Search will reject.
     * When <code>true</code> the validation errors are logged 
     * instead and the faulty field or document is not committed.
     * @return <code>true</code> when ignoring validation errors
     */
    public boolean isIgnoreValidationErrors() {
        return ignoreValidationErrors;
    }
    /**
     * Sets whether to ignore validation errors.  
     * When <code>false</code>, an exception is 
     * thrown if a document contains a field that Azure Search will reject.  
     * When <code>true</code> the validation errors are logged 
     * instead and the faulty field or document is not committed.
     * @param ignoreValidationErrors <code>true</code> when ignoring validation 
     *        errors
     */
    public void setIgnoreValidationErrors(boolean ignoreValidationErrors) {
        this.ignoreValidationErrors = ignoreValidationErrors;
    }
    
    /**
     * Whether to ignore response errors.  By default, an exception is 
     * thrown if the Azure Search response contains an error.  
     * When <code>true</code> the errors are logged instead.
     * @return <code>true</code> when ignoring response errors
     */
    public boolean isIgnoreResponseErrors() {
        return ignoreResponseErrors;
    }
    /**
     * Sets whether to ignore response errors.  
     * When <code>false</code>, an exception is 
     * thrown if the Azure Search response contains an error.  
     * When <code>true</code> the errors are logged instead.
     * @param ignoreResponseErrors <code>true</code> when ignoring response 
     *        errors
     */
    public void setIgnoreResponseErrors(boolean ignoreResponseErrors) {
        this.ignoreResponseErrors = ignoreResponseErrors;
    }    

    /**
     * Gets the proxy settings. Never <code>null</code>.
     * @return proxy settings
     * @since 1.1.0
     */
    public ProxySettings getProxySettings() {
        return proxySettings;
    }

    /**
     * Whether to use integrated Windows Authentication (if applicable).
     * @return <code>true</code> if using Windows Authentication
     */
    public boolean isUseWindowsAuth() {
        return useWindowsAuth;
    }
    /**
     * Sets whether to use integrated Windows Authentication (if applicable).
     * @param useWindowsAuth <code>true</code> if using Windows Authentication
     */
    public void setUseWindowsAuth(boolean useWindowsAuth) {
        this.useWindowsAuth = useWindowsAuth;
    }

    @Override
    public void commit() {
        super.commit();
        closeIfDone();
    }

    //TODO The following is a workaround to not having
    // a close() method (or equivalent) on the Committers yet.
    // So we check that the caller is not itself, which means it should
    // be the parent framework, which should in theory, call this only 
    // once. This is safe to do as the worst case scenario is that a new
    // client is re-created.
    // Remove this method once proper init/close is added to Committers
    private void closeIfDone() {
        StackTraceElement[] els = Thread.currentThread().getStackTrace();
        for (StackTraceElement el : els) {
            if (AbstractCommitter.class.getName().equals(el.getClassName())
                    && "commitIfReady".equals(el.getMethodName())) {
                return;
            }
        }
        close();
    }
    protected void close() {
        IOUtils.closeQuietly(client);
        client = null;
        LOG.info("Azure Search REST API Http Client closed.");
    }
    
    @Override
    protected void commitBatch(List<ICommitOperation> batch) {
        HttpClient safeClient = nullSafeHttpClient();
        
        LOG.info("Sending " + batch.size() 
                + " commit operations to Azure Search.");
        try {
            boolean first = true;
            StringBuilder json = new StringBuilder();
            for (ICommitOperation op : batch) {
                String toAppend;
                if (op instanceof IAddOperation) {
                    toAppend = buildAddOperationJSON((IAddOperation) op);
                } else if (op instanceof IDeleteOperation) {
                    toAppend = buildDeleteOperationJSON((IDeleteOperation) op); 
                } else {
                    close();
                    throw new CommitterException("Unsupported operation:" + op);
                }
                if (StringUtils.isNotBlank(toAppend)) {
                    if (!first) {
                        json.append(",\n");
                    }
                    json.append(toAppend);
                    first = false;
                }
            }
            
            if (json.length() == 0) {
                LOG.warn("No documents were valid. Nothing committed.");
                return;
            }

            json.insert(0, "{\"value\":[\n");
            json.append("\n]}\n");
            
            if (LOG.isTraceEnabled()) {
                LOG.trace("JSON POST:\n" + StringUtils.trim(json.toString()));
            }
            StringEntity requestEntity = new StringEntity(
                    json.toString(), ContentType.APPLICATION_JSON);

            HttpPost post = new HttpPost(restURL);
            post.addHeader("api-key", getApiKey());
            post.setEntity(requestEntity);
            HttpResponse response = safeClient.execute(post);
            handleResponse(response);
            post.releaseConnection();
            LOG.info("Done sending commit operations to Azure Search.");
        } catch (CommitterException e) {
            close();
            throw e;
        } catch (Exception e) {
            close();
            throw new CommitterException(
                    "Could not commit JSON batch to Azure Search.", e);
        }
    }

    private void handleResponse(HttpResponse response) 
            throws IOException {
        HttpEntity respEntity = response.getEntity();
        String responseAsString = "";
        if (respEntity != null) {
            responseAsString = IOUtils.toString(
                    respEntity.getContent(), StandardCharsets.UTF_8);
        }
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode != HttpStatus.SC_OK 
                && statusCode != HttpStatus.SC_CREATED) {
            String error = "Invalid HTTP response: \""
                    + response.getStatusLine()
                    + "\". Azure Response: " + responseAsString;
            if (isIgnoreResponseErrors()) {
                LOG.error(error);
            } else {
                close();
                throw new CommitterException(error);
            }            
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Azure Search response status: " 
                        + response.getStatusLine());
            }
            if (LOG.isTraceEnabled()) {
                LOG.trace("Azure Search response:\n" + responseAsString);
            }
        }
    }
    
    private String buildAddOperationJSON(IAddOperation add) {
        String docId = add.getMetadata().getString(getTargetReferenceField());
        if (StringUtils.isBlank(docId)) {
            docId = add.getReference();
        }
        
        // if allow unsafe... do not encode
        if (disableReferenceEncoding) {
            if (!validateDocumentKey(docId)) {
                return null;
            }
        } else {
            docId = Base64.encodeBase64URLSafeString(docId.getBytes());
        }
        
        StringBuilder json = new StringBuilder();
        json.append("{\"@search.action\": \"upload\",");
        append(json, getTargetReferenceField(), docId);
        for (Entry<String, List<String>> entry : add.getMetadata().entrySet()) {
            String field = entry.getKey();
            
            // Since target ID was already added (needs to be first), we do 
            // not add it again here
            if (Objects.equals(getTargetReferenceField(), field)) {
                continue;
            }
            if (validateFieldName(field)) {
                json.append(',');
                append(json, field, entry.getValue());
            }
        }
        json.append("}");
        return json.toString();
    }

    private boolean validateFieldName(String field) {
        if (field.startsWith("azureSearch")) {
            return validationError("Document field cannot begin "
                    + "with \"azureSearch\": " + field);
        }
        if (!field.matches("[A-Za-z0-9_]+")) {
            return validationError("Document field cannot have "
                    + "one or more characters other than letters, "
                    + "numbers and underscores: " + field);
        }
        if (field.length() > 128) {
            return validationError("Document field cannot be "
                    + "longer than 128 characters: " + field);
        }
        return true;
    }
    private boolean validateDocumentKey(String docId) {
        if (docId.startsWith("_")) {
            return validationError("Document reference cannot start "
                    + "with an underscore character: " + docId);
        }
        if (!docId.matches("[A-Za-z0-9_\\-=]+")) {
            return validationError("Document reference cannot have one or more "
                    + "characters other than letters, numbers, dashes, "
                    + "underscores, and equal signs: " + docId);
        }
        return true;
    }
    
    private boolean validationError(String error) {
        if (isIgnoreValidationErrors()) {
            LOG.error(error);
            return false;
        }
        throw new CommitterException(error);
    }

    private String buildDeleteOperationJSON(IDeleteOperation del) {
        String docId = Base64.encodeBase64URLSafeString(
                del.getReference().getBytes());
        StringBuilder json = new StringBuilder();
        json.append("{\"@search.action\": \"delete\",");
        append(json, getTargetReferenceField(), docId);
        json.append("}");
        return json.toString();
    }

    private void append(StringBuilder json, String field, List<String> values) {
        if (values.size() == 1) {
            append(json, field, values.get(0));
            return;
        }
        json.append('"')
            .append(StringEscapeUtils.escapeJson(field))
            .append("\":[");
        boolean first = true;
        for (String value : values) {
            if (!first) {
                json.append(',');
            }
            json.append('"')
            .append(StringEscapeUtils.escapeJson(value))
            .append("\"");
            first = false;
        }
        json.append(']');
    }
    
    private void append(StringBuilder json, String field, String value) {
        json.append('"')
            .append(StringEscapeUtils.escapeJson(field))
            .append("\":\"")
            .append(StringEscapeUtils.escapeJson(value))
            .append("\"");
    }
    
    private synchronized CloseableHttpClient nullSafeHttpClient() {
        if (client == null) {
            if (StringUtils.isBlank(getEndpoint())) {
                throw new CommitterException("Endpoint is undefined.");
            }
            if (StringUtils.isBlank(getApiKey())) {
                throw new CommitterException("API admin key is undefined.");
            }
            if (StringUtils.isBlank(getIndexName())) {
                throw new CommitterException("Index name is undefined.");
            }
            if (getCommitBatchSize() > 1000) {
                throw new CommitterException(
                        "Commit batch size cannot be greater than 1000.");
            }
            
            String version = ObjectUtils.defaultIfNull(
                    getApiVersion(), DEFAULT_API_VERSION);
            LOG.debug("Azure Search API Version: " + version);
            
            HttpClientBuilder httpBuilder;
            if (useWindowsAuth && WinHttpClients.isWinAuthAvailable()) {
                httpBuilder = WinHttpClients.custom();
            } else {
                httpBuilder = HttpClientBuilder.create();
            }
            buildHttpClient(httpBuilder);
            client = httpBuilder.build();
            restURL = getEndpoint() + "/indexes/" + getIndexName()
                    + "/docs/index?api-version=" + version;
        }
        return client;
    }

    protected void buildHttpClient(HttpClientBuilder builder) {
        if (proxySettings.isSet()) {
            builder.setProxy(proxySettings.createHttpHost());
            builder.setDefaultCredentialsProvider(
                    proxySettings.createCredentialsProvider());
        }
        builder.setMaxConnTotal(20);
        builder.setMaxConnPerRoute(10);
    }
    
    @Override
    protected void saveToXML(XMLStreamWriter writer) throws XMLStreamException {
        EnhancedXMLStreamWriter w = new EnhancedXMLStreamWriter(writer);
        w.writeElementString("endpoint", getEndpoint());
        w.writeElementString("apiKey", getApiKey());
        w.writeElementString("apiVersion", getApiVersion());
        w.writeElementString("indexName", getIndexName());
        w.writeElementBoolean("useWindowsAuth", isUseWindowsAuth());
        w.writeElementBoolean(
                "disableReferenceEncoding", isDisableReferenceEncoding());
        w.writeElementBoolean(
                "ignoreValidationErrors", isIgnoreValidationErrors());
        w.writeElementBoolean("ignoreResponseErrors", isIgnoreResponseErrors());
        proxySettings.saveProxyToXML(w);
    }

    @Override
    protected void loadFromXml(XMLConfiguration xml) {
        setEndpoint(xml.getString("endpoint", getEndpoint()));
        setApiKey(xml.getString("apiKey", getApiKey()));
        setApiVersion(xml.getString("apiVersion", getApiVersion()));
        setIndexName(xml.getString("indexName", getIndexName()));
        setUseWindowsAuth(xml.getBoolean("useWindowsAuth", isUseWindowsAuth()));
        setDisableReferenceEncoding(xml.getBoolean("disableReferenceEncoding", 
                isDisableReferenceEncoding()));
        setIgnoreValidationErrors(xml.getBoolean("ignoreValidationErrors", 
                isIgnoreValidationErrors()));
        setIgnoreResponseErrors(xml.getBoolean(
                "ignoreResponseErrors", isIgnoreResponseErrors()));
        proxySettings.loadProxyFromXML(xml);
    }
    
    @Override
    public boolean equals(final Object other) {
        return EqualsBuilder.reflectionEquals(this, other, "client", "restURL");
    }
    @Override
    public int hashCode() {
        return HashCodeBuilder.reflectionHashCode(this,  "client", "restURL");
    }
    @Override
    public String toString() {
        return new ReflectionToStringBuilder(
                this, ToStringStyle.SHORT_PREFIX_STYLE)
            .setExcludeFieldNames("client", "restURL").toString();
    }    
}
