/*
 * *****************************************************************************
 *      Cloud Foundry
 *      Copyright (c) [2009-2015] Pivotal Software, Inc. All Rights Reserved.
 *      This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *      You may not use this product except in compliance with the License.
 *
 *      This product includes a number of subcomponents with
 *      separate copyright notices and license terms. Your use of these
 *      subcomponents is subject to the terms and conditions of the
 *      subcomponent's license, as noted in the LICENSE file.
 * *****************************************************************************
 */
package org.cloudfoundry.identity.uaa.mock.saml;

import org.cloudfoundry.identity.uaa.TestSpringContext;
import org.cloudfoundry.identity.uaa.provider.saml.idp.SamlTestUtils;
import org.cloudfoundry.identity.uaa.saml.SamlKey;
import org.cloudfoundry.identity.uaa.test.HoneycombAuditEventTestListenerExtension;
import org.cloudfoundry.identity.uaa.test.HoneycombJdbcInterceptorExtension;
import org.cloudfoundry.identity.uaa.test.TestClient;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.SamlConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.w3c.dom.NodeList;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.EMPTY_MAP;
import static org.cloudfoundry.identity.uaa.provider.saml.SamlKeyManagerFactoryTests.*;
import static org.cloudfoundry.identity.uaa.provider.saml.idp.SamlTestUtils.getCertificates;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_XML;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Configuration
class TestClientMockMvc {
    @Bean
    public MockMvc mockMvc(
            WebApplicationContext webApplicationContext,
            @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") FilterChainProxy springSecurityFilterChain
    ) {
        return MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilter(springSecurityFilterChain)
                .build();
    }

    @Bean
    public TestClient testClient(
            MockMvc mockMvc
    ) {
        return new TestClient(mockMvc);
    }
}

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(SpringExtension.class)
@ExtendWith(HoneycombJdbcInterceptorExtension.class)
@ExtendWith(HoneycombAuditEventTestListenerExtension.class)
@ActiveProfiles("default")
@WebAppConfiguration
@ContextConfiguration(classes = {
        TestSpringContext.class,
        TestClientMockMvc.class
})
@interface DefaultTestContext {
}

// TODO: This class has a lot of helpers, why?
@DefaultTestContext
abstract class SamlKeyRotationMockMvcTests {

    SamlKeyRotationMockMvcTests(String url) {
        this.url = url;
    }

    private String url;
    private IdentityZone zone;
    private String token;
    private SamlKey samlKey1;
    private SamlKey samlKey2;

    private MockMvc mockMvc;

    @BeforeEach
    void createZone(
            @Autowired TestClient testClient,
            @Autowired MockMvc mockMvc
    ) throws Exception {
        token = testClient.getClientCredentialsOAuthAccessToken(
                "identity",
                "identitysecret",
                "zones.write");
        this.mockMvc = mockMvc;

        String id = new RandomValueStringGenerator().generate().toLowerCase();
        IdentityZone identityZone = new IdentityZone();
        identityZone.setId(id);
        identityZone.setSubdomain(id);
        identityZone.setName("Test Saml Key Zone");
        identityZone.setDescription("Testing SAML Key Rotation");
        Map<String, String> keys = new HashMap<>();
        keys.put("exampleKeyId", "s1gNiNg.K3y/t3XT");
        identityZone.getConfig().getTokenPolicy().setKeys(keys);
        SamlConfig samlConfig = new SamlConfig();
        samlConfig.setCertificate(legacyCertificate);
        samlConfig.setPrivateKey(legacyKey);
        samlConfig.setPrivateKeyPassword(legacyPassphrase);
        samlKey1 = new SamlKey(key1, passphrase1, certificate1);
        samlConfig.addKey("key1", samlKey1);
        samlKey2 = new SamlKey(key2, passphrase2, certificate2);
        samlConfig.addKey("key2", samlKey2);
        identityZone.getConfig().setSamlConfig(samlConfig);

        updateZone(identityZone, true);
    }

    private void updateZone(IdentityZone identityZone, boolean create) throws Exception {
        if (create) {
            String zoneJson = mockMvc.perform(
                    post("/identity-zones")
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content(JsonUtils.writeValueAsString(identityZone)))
                    .andExpect(status().is(HttpStatus.CREATED.value()))
                    .andReturn().getResponse().getContentAsString();

            zone = JsonUtils.readValue(zoneJson, IdentityZone.class);
        } else {
            String zoneJson = mockMvc.perform(
                    put("/identity-zones/" + zone.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content(JsonUtils.writeValueAsString(identityZone)))
                    .andExpect(status().is(HttpStatus.OK.value()))
                    .andReturn().getResponse().getContentAsString();

            zone = JsonUtils.readValue(zoneJson, IdentityZone.class);
        }
    }

    @Test
    void key_rotation() throws Exception {
        //default with three keys
        String metadata = getMetadata(url);
        List<String> signatureVerificationKeys = getCertificates(metadata, "signing");
        assertThat(signatureVerificationKeys, containsInAnyOrder(clean(legacyCertificate), clean(certificate1), clean(certificate2)));
        List<String> encryptionKeys = getCertificates(metadata, "encryption");
        assertThat(encryptionKeys, containsInAnyOrder(clean(legacyCertificate)));
        evaluateSignatureKey(metadata, legacyCertificate);

        //activate key1
        zone.getConfig().getSamlConfig().setActiveKeyId("key1");
        updateZone(zone, false);
        metadata = getMetadata(url);
        signatureVerificationKeys = getCertificates(metadata, "signing");
        assertThat(signatureVerificationKeys, containsInAnyOrder(clean(legacyCertificate), clean(certificate1), clean(certificate2)));
        encryptionKeys = getCertificates(metadata, "encryption");
        evaluateSignatureKey(metadata, certificate1);
        assertThat(encryptionKeys, containsInAnyOrder(clean(certificate1)));

        //remove all but key2
        zone.getConfig().getSamlConfig().setKeys(EMPTY_MAP);
        zone.getConfig().getSamlConfig().addAndActivateKey("key2", samlKey2);
        updateZone(zone, false);
        metadata = getMetadata(url);
        signatureVerificationKeys = getCertificates(metadata, "signing");
        assertThat(signatureVerificationKeys, containsInAnyOrder(clean(certificate2)));
        evaluateSignatureKey(metadata, certificate2);
        encryptionKeys = getCertificates(metadata, "encryption");
        assertThat(encryptionKeys, containsInAnyOrder(clean(certificate2)));
    }

    @Test
    void check_metadata_signature_key() throws Exception {
        String metadata = getMetadata(url);

        evaluateSignatureKey(metadata, legacyCertificate);

        zone.getConfig().getSamlConfig().setActiveKeyId("key1");
        updateZone(zone, false);

        metadata = getMetadata(url);

        evaluateSignatureKey(metadata, certificate1);
    }

    private String getMetadata(String uri) throws Exception {
        return mockMvc.perform(
                get(uri)
                        .header("Host", zone.getSubdomain() + ".localhost")
                        .accept(APPLICATION_XML)
        )
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String clean(String cert) {
        return cert.replace("-----BEGIN CERTIFICATE-----", "").replace("-----END CERTIFICATE-----", "").replace("\n", "");
    }

    private void evaluateSignatureKey(String metadata, String expectedKey) throws Exception {
        String xpath = "//*[local-name() = 'Signature']//*[local-name() = 'X509Certificate']/text()";
        NodeList nodeList = SamlTestUtils.evaluateXPathExpression(SamlTestUtils.getMetadataDoc(metadata), xpath);
        assertNotNull(nodeList);
        assertEquals(1, nodeList.getLength());
        assertEquals(clean(expectedKey), clean(nodeList.item(0).getNodeValue()));
    }

}
