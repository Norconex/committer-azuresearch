/* Copyright 2017-2020 Norconex Inc.
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
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.ClassUtils;
import org.apache.log4j.Level;
import org.junit.Assert;
import org.junit.Test;

import com.norconex.commons.lang.config.XMLConfigurationUtil;
import com.norconex.commons.lang.encrypt.EncryptionKey;
import com.norconex.commons.lang.encrypt.EncryptionKey.Source;
import com.norconex.commons.lang.log.CountingConsoleAppender;
import com.norconex.commons.lang.net.ProxySettings;

public class AzureSearchCommitterConfigTest {

    @Test
    public void testWriteRead() throws Exception {
        AzureSearchCommitter committer = new AzureSearchCommitter();
        committer.setQueueDir("my-queue-dir");
        committer.setSourceContentField("sourceContentField");
        committer.setTargetContentField("targetContentField");
        committer.setSourceReferenceField("idField");
        committer.setKeepSourceContentField(true);
        committer.setKeepSourceReferenceField(false);
        committer.setQueueSize(10);
        committer.setCommitBatchSize(1);
        committer.setEndpoint("endpoint");
        committer.setApiVersion("apiVersion");
        committer.setApiKey("apiKey");
        committer.setIndexName("indexName");
        committer.setDisableReferenceEncoding(true);
        committer.setIgnoreValidationErrors(true);
        committer.setIgnoreResponseErrors(true);
        committer.setArrayFields(".*");
        committer.setArrayFieldsRegex(true);

        ProxySettings ps = committer.getProxySettings();
        ps.setProxyHost("myhost");
        ps.setProxyPassword("mypassword");
        ps.setProxyPasswordKey(new EncryptionKey("keyvalue", Source.KEY));
        ps.setProxyPort(99);
        ps.setProxyRealm("realm");
        ps.setProxyScheme("sheme");
        ps.setProxyUsername("username");

        System.out.println("Writing/Reading this: " + committer);
        XMLConfigurationUtil.assertWriteRead(committer);
    }

    @Test
    public void testAppendArray() throws IOException {
        AzureSearchCommitter c = new AzureSearchCommitter();
        List<String> singleValue = Arrays.asList("v1");
        List<String> multiValues = Arrays.asList("v1", "v2", "v3");

        String expectedSingleAsArray = "\"f2\":[\"v1\"]";
        String expectedSingleAsSingle = "\"f2\":\"v1\"";
        String expectedMulti = "\"f2\":[\"v1\",\"v2\",\"v3\"]";

        // Force array: YES.  Format: CSV.  Match: YES
        c.setArrayFields("f1, f2, f3");
        c.setArrayFieldsRegex(false);
        assertAppend(c, singleValue, expectedSingleAsArray);
        assertAppend(c, multiValues, expectedMulti);

        // Force array: YES.  Format: REGEX.  Match: NO
        c.setArrayFields("f1, f2, f3");
        c.setArrayFieldsRegex(true);
        assertAppend(c, singleValue, expectedSingleAsSingle);
        assertAppend(c, multiValues, expectedMulti);

        // Force array: YES.  Format: CSV.  Match: NO
        c.setArrayFields("f.*");
        c.setArrayFieldsRegex(false);
        assertAppend(c, singleValue, expectedSingleAsSingle);
        assertAppend(c, multiValues, expectedMulti);

        // Force array: YES.  Format: REGEX.  Match: YES
        c.setArrayFields("f.*");
        c.setArrayFieldsRegex(true);
        assertAppend(c, singleValue, expectedSingleAsArray);
        assertAppend(c, multiValues, expectedMulti);

        // Force array: NO.  Format: N/A.  Match: N/A
        c.setArrayFields("");
        c.setArrayFieldsRegex(true);
        assertAppend(c, singleValue, expectedSingleAsSingle);
        assertAppend(c, multiValues, expectedMulti);
    }

    private void assertAppend(
            AzureSearchCommitter c, List<String> testValues, String expected) {
        StringBuilder b = new StringBuilder();
        c.append(b, "f2", testValues);
        Assert.assertEquals(expected, b.toString());
    }

    @Test
    public void testValidation() throws IOException {
        CountingConsoleAppender appender = new CountingConsoleAppender();
        appender.startCountingFor(XMLConfigurationUtil.class, Level.WARN);
        try (Reader r = new InputStreamReader(getClass().getResourceAsStream(
                ClassUtils.getShortClassName(getClass()) + ".xml"))) {
            XMLConfigurationUtil.newInstance(r);
        } finally {
            appender.stopCountingFor(XMLConfigurationUtil.class);
        }
        Assert.assertEquals("Validation warnings/errors were found.",
                0, appender.getCount());
    }
}
