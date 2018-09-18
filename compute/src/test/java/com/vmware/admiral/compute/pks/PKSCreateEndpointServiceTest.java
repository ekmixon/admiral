/*
 * Copyright (c) 2018 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.compute.pks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.function.Consumer;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.vmware.admiral.common.DeploymentProfileConfig;
import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.admiral.common.util.QueryUtil;
import com.vmware.admiral.compute.container.ComputeBaseTest;
import com.vmware.admiral.compute.pks.PKSCreateEndpointService.EndpointSpec;
import com.vmware.admiral.compute.pks.PKSEndpointService.Endpoint;
import com.vmware.xenon.common.LocalizableValidationException;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceErrorResponse;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.test.TestRequestSender;

public class PKSCreateEndpointServiceTest extends ComputeBaseTest {

    private TestRequestSender sender;

    @BeforeClass
    public static void beforeClass() {
        DeploymentProfileConfig.getInstance().setTest(false);
    }

    @AfterClass
    public static void afterClass() {
        DeploymentProfileConfig.getInstance().setTest(true);
    }

    @Before
    public void setUp() throws Throwable {
        waitForServiceAvailability(PKSEndpointFactoryService.SELF_LINK,
                PKSCreateEndpointService.SELF_LINK);
        sender = host.getTestRequestSender();
    }

    @Test
    public void testCreate() throws Throwable {
        Endpoint endpoint = new Endpoint();
        endpoint.apiEndpoint = "https://localhost";
        endpoint.uaaEndpoint = "https://localhost";

        EndpointSpec endpointSpec = new EndpointSpec();
        endpointSpec.acceptHostAddress = true;
        endpointSpec.acceptCertificate = true;
        endpointSpec.endpoint = endpoint;

        createEndpoint(endpointSpec);

        endpoint = new Endpoint();
        endpoint.apiEndpoint = "https://localhost";
        endpoint.uaaEndpoint = "https://localhost";
        endpointSpec.endpoint = endpoint;

        createEndpointExpectFailure(endpointSpec, failure -> {
            assertTrue(failure.failure instanceof LocalizableValidationException);
            ServiceErrorResponse errorResponse = failure.op.getBody(ServiceErrorResponse.class);
            assertNotNull(errorResponse);
        });
    }

    @Test
    public void testCreateEmptyBody() {
        createEndpointExpectFailure(null, failure -> {
            assertTrue(failure.failure instanceof IllegalArgumentException);
            assertEquals("Body is required", failure.failure.getMessage());
        });
    }

    @Test
    public void testCreateTheSameEndpointInDifferentProjectFails() throws Throwable {
        Endpoint endpoint = new Endpoint();
        endpoint.apiEndpoint = "https://localhost";
        endpoint.uaaEndpoint = "https://localhost";
        endpoint.tenantLinks = Collections
                .singletonList(QueryUtil.PROJECT_IDENTIFIER + "some-project");

        EndpointSpec endpointSpec = new EndpointSpec();
        endpointSpec.acceptHostAddress = true;
        endpointSpec.acceptCertificate = true;
        endpointSpec.endpoint = endpoint;

        createEndpoint(endpointSpec);

        endpoint = new Endpoint();
        endpoint.apiEndpoint = "https://localhost";
        endpoint.uaaEndpoint = "https://localhost";
        endpoint.tenantLinks = Collections
                .singletonList(QueryUtil.PROJECT_IDENTIFIER + "another-project");
        endpointSpec.endpoint = endpoint;

        createEndpointExpectFailure(endpointSpec, failure -> {
            assertTrue(failure.failure instanceof LocalizableValidationException);
            ServiceErrorResponse errorResponse = failure.op.getBody(ServiceErrorResponse.class);
            assertNotNull(errorResponse);
        });
    }

    @Test
    public void testCreateTheSameEndpointInDifferentGroupFails() throws Throwable {
        final String tenantLink = QueryUtil.TENANT_IDENTIFIER + "some-tenant";

        Endpoint endpoint = new Endpoint();
        endpoint.apiEndpoint = "https://localhost";
        endpoint.uaaEndpoint = "https://localhost";
        endpoint.tenantLinks = Collections
                .singletonList(tenantLink + QueryUtil.GROUP_IDENTIFIER + "some-group");

        EndpointSpec endpointSpec = new EndpointSpec();
        endpointSpec.acceptHostAddress = true;
        endpointSpec.acceptCertificate = true;
        endpointSpec.endpoint = endpoint;

        createEndpoint(endpointSpec);

        endpoint = new Endpoint();
        endpoint.apiEndpoint = "https://localhost";
        endpoint.uaaEndpoint = "https://localhost";
        endpoint.tenantLinks = Collections
                .singletonList(tenantLink + QueryUtil.GROUP_IDENTIFIER + "another-group");
        endpointSpec.endpoint = endpoint;

        createEndpointExpectFailure(endpointSpec, failure -> {
            assertTrue(failure.failure instanceof LocalizableValidationException);
            ServiceErrorResponse errorResponse = failure.op.getBody(ServiceErrorResponse.class);
            assertNotNull(errorResponse);
        });
    }

    @Test
    public void testCreateTheSameEndpointInDifferentTenantPasses() throws Throwable {
        Endpoint endpoint = new Endpoint();
        endpoint.apiEndpoint = "https://localhost";
        endpoint.uaaEndpoint = "https://localhost";
        endpoint.tenantLinks = Collections
                .singletonList(QueryUtil.TENANT_IDENTIFIER + "some-tenant");

        EndpointSpec endpointSpec = new EndpointSpec();
        endpointSpec.acceptHostAddress = true;
        endpointSpec.acceptCertificate = true;
        endpointSpec.endpoint = endpoint;

        createEndpoint(endpointSpec);

        endpoint = new Endpoint();
        endpoint.apiEndpoint = "https://localhost";
        endpoint.uaaEndpoint = "https://localhost";
        endpoint.tenantLinks = Collections
                .singletonList(QueryUtil.TENANT_IDENTIFIER + "another-tenant");
        endpointSpec.endpoint = endpoint;

        createEndpoint(endpointSpec);
    }

    @Test
    public void testUpdate() throws Throwable {
        Endpoint endpoint = new Endpoint();
        endpoint.apiEndpoint = "https://localhost";
        endpoint.uaaEndpoint = "https://localhost";

        EndpointSpec endpointSpec = new EndpointSpec();
        endpointSpec.acceptHostAddress = true;
        endpointSpec.acceptCertificate = true;
        endpointSpec.endpoint = endpoint;

        createEndpoint(endpointSpec);

        endpoint.uaaEndpoint = "http://some-other-host";
        endpointSpec.isUpdateOperation = true;

        Endpoint updatedEndpoint = createEndpoint(endpointSpec);
        assertEquals(endpoint.uaaEndpoint, updatedEndpoint.uaaEndpoint);
    }

    @Test
    public void testStoreCertLinkInEndpoint() {
        EndpointSpec spec = new EndpointSpec();
        Endpoint endpoint = new Endpoint();
        spec.endpoint = endpoint;
        assertNull(endpoint.customProperties);

        final String testProp = "test-property";
        final String testValue = "test-value";

        PKSCreateEndpointService.storeCertLinkInEndpoint(spec, testProp, testValue);
        assertNotNull(endpoint.customProperties);
        assertEquals(testValue, endpoint.customProperties.get(testProp));
    }

    @Test
    public void testValidate() {
        Endpoint endpoint = new Endpoint();
        endpoint.uaaEndpoint = ":-13";
        endpoint.apiEndpoint = ":-17";

        EndpointSpec endpointSpec = new EndpointSpec();
        endpointSpec.acceptHostAddress = true;
        endpointSpec.acceptCertificate = true;
        endpointSpec.endpoint = endpoint;

        createEndpointExpectFailure(endpointSpec, failure -> {
            assertTrue(failure.failure instanceof LocalizableValidationException);
            ServiceErrorResponse errorResponse = failure.op.getBody(ServiceErrorResponse.class);
            assertNotNull(errorResponse);
            assertEquals("Invalid host address: :-13", errorResponse.message);
        });

        endpoint.uaaEndpoint = "file://local.file";
        createEndpointExpectFailure(endpointSpec, failure -> {
            assertTrue(failure.failure instanceof LocalizableValidationException);
            ServiceErrorResponse errorResponse = failure.op.getBody(ServiceErrorResponse.class);
            assertNotNull(errorResponse);
            assertEquals("Unsupported scheme, must be http or https: file", errorResponse.message);
        });

        endpoint.uaaEndpoint = "http://localhost";
        createEndpointExpectFailure(endpointSpec, failure -> {
            assertTrue(failure.failure instanceof LocalizableValidationException);
            ServiceErrorResponse errorResponse = failure.op.getBody(ServiceErrorResponse.class);
            assertNotNull(errorResponse);
            assertEquals("Invalid host address: :-17", errorResponse.message);
        });

        endpoint.uaaEndpoint = "jar://local.file";
        createEndpointExpectFailure(endpointSpec, failure -> {
            assertTrue(failure.failure instanceof LocalizableValidationException);
            ServiceErrorResponse errorResponse = failure.op.getBody(ServiceErrorResponse.class);
            assertNotNull(errorResponse);
            assertEquals("Unsupported scheme, must be http or https: jar", errorResponse.message);
        });
    }

    @Test
    public void testValidateConnection() {
        // validate

        Endpoint endpoint = new Endpoint();
        endpoint.apiEndpoint = "https://localhost";
        endpoint.uaaEndpoint = "https://localhost";

        EndpointSpec endpointSpec = new EndpointSpec();
        endpointSpec.acceptHostAddress = true;
        endpointSpec.acceptCertificate = true;
        endpointSpec.endpoint = endpoint;

        Operation o = Operation
                .createPut(UriUtils.buildUri(host, PKSCreateEndpointService.SELF_LINK,
                        ManagementUriParts.REQUEST_PARAM_VALIDATE_OPERATION_NAME))
                .setBodyNoCloning(endpointSpec);

        TestRequestSender.FailureResponse failure = sender.sendAndWaitFailure(o);
        assertNotNull(failure);
        assertTrue(failure.failure.getMessage().startsWith("Unexpected error: Service not found:"));
        assertTrue(failure.failure.getMessage().endsWith("/adapters/pks-service"));
    }

    private Endpoint createEndpoint(EndpointSpec endpointSpec) throws Throwable {
        Operation o = Operation
                .createPut(host, PKSCreateEndpointService.SELF_LINK)
                .setBodyNoCloning(endpointSpec);

        o = sender.sendAndWait(o);
        assertNotNull(o);
        String locationHeader = o.getResponseHeader(Operation.LOCATION_HEADER);
        assertNotNull(locationHeader);

        Endpoint result = getDocumentNoWait(Endpoint.class, locationHeader);

        assertNotNull(result);
        assertNotNull(result.documentSelfLink);
        assertEquals(endpointSpec.endpoint.uaaEndpoint, result.uaaEndpoint);
        assertEquals(endpointSpec.endpoint.apiEndpoint, result.apiEndpoint);

        return result;
    }

    private void createEndpointExpectFailure(EndpointSpec e,
            Consumer<TestRequestSender.FailureResponse> consumer) {
        Operation o = Operation
                .createPut(host, PKSCreateEndpointService.SELF_LINK)
                .setBodyNoCloning(e);
        TestRequestSender.FailureResponse failure = sender.sendAndWaitFailure(o);
        consumer.accept(failure);
    }

}