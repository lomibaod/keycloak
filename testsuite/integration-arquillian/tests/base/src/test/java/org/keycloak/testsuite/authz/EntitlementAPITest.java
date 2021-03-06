/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
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
 */
package org.keycloak.testsuite.authz;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.hamcrest.Matchers;
import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.resource.AuthorizationResource;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.ClientsResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.authorization.client.AuthorizationDeniedException;
import org.keycloak.authorization.client.AuthzClient;
import org.keycloak.authorization.client.Configuration;
import org.keycloak.authorization.client.representation.TokenIntrospectionResponse;
import org.keycloak.authorization.client.util.HttpResponseException;
import org.keycloak.common.util.Base64Url;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.AccessToken.Authorization;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.authorization.AuthorizationRequest;
import org.keycloak.representations.idm.authorization.AuthorizationRequest.Metadata;
import org.keycloak.representations.idm.authorization.AuthorizationResponse;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.JSPolicyRepresentation;
import org.keycloak.representations.idm.authorization.Permission;
import org.keycloak.representations.idm.authorization.PermissionRequest;
import org.keycloak.representations.idm.authorization.PermissionResponse;
import org.keycloak.representations.idm.authorization.PermissionTicketRepresentation;
import org.keycloak.representations.idm.authorization.ResourcePermissionRepresentation;
import org.keycloak.representations.idm.authorization.ResourceRepresentation;
import org.keycloak.representations.idm.authorization.ScopePermissionRepresentation;
import org.keycloak.representations.idm.authorization.UserPolicyRepresentation;
import org.keycloak.testsuite.util.ClientBuilder;
import org.keycloak.testsuite.util.OAuthClient;
import org.keycloak.testsuite.util.RealmBuilder;
import org.keycloak.testsuite.util.RoleBuilder;
import org.keycloak.testsuite.util.RolesBuilder;
import org.keycloak.testsuite.util.UserBuilder;
import org.keycloak.util.JsonSerialization;

/**
 * @author <a href="mailto:psilva@redhat.com">Pedro Igor</a>
 */
public class EntitlementAPITest extends AbstractAuthzTest {

    private static final String RESOURCE_SERVER_TEST = "resource-server-test";
    private static final String TEST_CLIENT = "test-client";
    private static final String AUTHZ_CLIENT_CONFIG = "default-keycloak.json";
    private static final String PAIRWISE_RESOURCE_SERVER_TEST = "pairwise-resource-server-test";
    private static final String PAIRWISE_TEST_CLIENT = "test-client-pairwise";
    private static final String PAIRWISE_AUTHZ_CLIENT_CONFIG = "default-keycloak-pairwise.json";
    private static final String PUBLIC_TEST_CLIENT = "test-public-client";
    private static final String PUBLIC_TEST_CLIENT_CONFIG = "default-keycloak-public-client.json";

    private AuthzClient authzClient;

    @ArquillianResource
    protected ContainerController controller;

    @Override
    public void addTestRealms(List<RealmRepresentation> testRealms) {
        testRealms.add(RealmBuilder.create().name("authz-test")
                .roles(RolesBuilder.create().realmRole(RoleBuilder.create().name("uma_authorization").build()))
                .user(UserBuilder.create().username("marta").password("password").addRoles("uma_authorization"))
                .user(UserBuilder.create().username("kolo").password("password"))
                .user(UserBuilder.create().username("offlineuser").password("password").addRoles("offline_access"))
                .client(ClientBuilder.create().clientId(RESOURCE_SERVER_TEST)
                        .secret("secret")
                        .authorizationServicesEnabled(true)
                        .redirectUris("http://localhost/resource-server-test")
                        .defaultRoles("uma_protection")
                        .directAccessGrants())
                .client(ClientBuilder.create().clientId(PAIRWISE_RESOURCE_SERVER_TEST)
                        .secret("secret")
                        .authorizationServicesEnabled(true)
                        .redirectUris("http://localhost/resource-server-test")
                        .defaultRoles("uma_protection")
                        .pairwise("http://pairwise.com")
                        .directAccessGrants())
                .client(ClientBuilder.create().clientId(TEST_CLIENT)
                        .secret("secret")
                        .authorizationServicesEnabled(true)
                        .redirectUris("http://localhost/test-client")
                        .directAccessGrants())
                .client(ClientBuilder.create().clientId(PAIRWISE_TEST_CLIENT)
                        .secret("secret")
                        .authorizationServicesEnabled(true)
                        .redirectUris("http://localhost/test-client")
                        .pairwise("http://pairwise.com")
                        .directAccessGrants())
                .client(ClientBuilder.create().clientId(PUBLIC_TEST_CLIENT)
                        .secret("secret")
                        .redirectUris("http://localhost:8180/auth/realms/master/app/auth/*")
                        .publicClient())
                .build());
    }

    @Before
    public void configureAuthorization() throws Exception {
        configureAuthorization(RESOURCE_SERVER_TEST);
        configureAuthorization(PAIRWISE_RESOURCE_SERVER_TEST);
    }

    @After
    public void removeAuthorization() throws Exception {
        removeAuthorization(RESOURCE_SERVER_TEST);
        removeAuthorization(PAIRWISE_RESOURCE_SERVER_TEST);
    }

    @Test
    public void testRptRequestWithoutResourceName() {
        testRptRequestWithoutResourceName(AUTHZ_CLIENT_CONFIG);
    }

    @Test
    public void testRptRequestWithoutResourceNamePairwise() {
        testRptRequestWithoutResourceName(PAIRWISE_AUTHZ_CLIENT_CONFIG);
    }

    public void testRptRequestWithoutResourceName(String configFile) {
        Metadata metadata = new Metadata();

        metadata.setIncludeResourceName(false);

        assertResponse(metadata, () -> {
            AuthorizationRequest request = new AuthorizationRequest();

            request.setMetadata(metadata);
            request.addPermission("Resource 1");

            return getAuthzClient(configFile).authorization("marta", "password").authorize(request);
        });
    }

    @Test
    public void testRptRequestWithResourceName() {
        testRptRequestWithResourceName(AUTHZ_CLIENT_CONFIG);
    }

    @Test
    public void testRptRequestWithResourceNamePairwise() {
        testRptRequestWithResourceName(PAIRWISE_AUTHZ_CLIENT_CONFIG);
    }

    @Test
    public void testInvalidRequestWithClaimsFromConfidentialClient() throws IOException {
        AuthorizationRequest request = new AuthorizationRequest();

        request.addPermission("Resource 13");
        HashMap<Object, Object> obj = new HashMap<>();

        obj.put("claim-a", "claim-a");

        request.setClaimToken(Base64Url.encode(JsonSerialization.writeValueAsBytes(obj)));

        assertResponse(new Metadata(), () -> getAuthzClient(AUTHZ_CLIENT_CONFIG).authorization("marta", "password").authorize(request));
    }

    @Test
    public void testInvalidRequestWithClaimsFromPublicClient() throws IOException {
        oauth.realm("authz-test");
        oauth.clientId(PUBLIC_TEST_CLIENT);

        oauth.doLogin("marta", "password");

        // Token request
        String code = oauth.getCurrentQuery().get(OAuth2Constants.CODE);
        OAuthClient.AccessTokenResponse response = oauth.doAccessTokenRequest(code, null);

        AuthorizationRequest request = new AuthorizationRequest();

        request.addPermission("Resource 13");
        HashMap<Object, Object> obj = new HashMap<>();

        obj.put("claim-a", "claim-a");

        request.setClaimToken(Base64Url.encode(JsonSerialization.writeValueAsBytes(obj)));

        try {
            getAuthzClient(AUTHZ_CLIENT_CONFIG).authorization(response.getAccessToken()).authorize(request);
        } catch (AuthorizationDeniedException expected) {
            assertEquals(403, HttpResponseException.class.cast(expected.getCause()).getStatusCode());
            assertTrue(HttpResponseException.class.cast(expected.getCause()).toString().contains("Public clients are not allowed to send claims"));
        }
    }

    @Test
    public void testRequestWithoutClaimsFromPublicClient() {
        oauth.realm("authz-test");
        oauth.clientId(PUBLIC_TEST_CLIENT);

        oauth.doLogin("marta", "password");

        // Token request
        String code = oauth.getCurrentQuery().get(OAuth2Constants.CODE);
        OAuthClient.AccessTokenResponse response = oauth.doAccessTokenRequest(code, null);

        AuthorizationRequest request = new AuthorizationRequest();

        request.addPermission("Resource 13");

        assertResponse(new Metadata(), () -> getAuthzClient(AUTHZ_CLIENT_CONFIG).authorization(response.getAccessToken()).authorize(request));
    }

    @Test
    public void testPermissionLimit() {
        testPermissionLimit(AUTHZ_CLIENT_CONFIG);
    }

    @Test
    public void testPermissionLimitPairwise() {
        testPermissionLimit(PAIRWISE_AUTHZ_CLIENT_CONFIG);
    }

    public void testPermissionLimit(String configFile) {
        AuthorizationRequest request = new AuthorizationRequest();

        for (int i = 1; i <= 10; i++) {
            request.addPermission("Resource " + i);
        }

        Metadata metadata = new Metadata();

        metadata.setLimit(10);

        request.setMetadata(metadata);

        AuthorizationResponse response = getAuthzClient(configFile).authorization("marta", "password").authorize(request);
        AccessToken rpt = toAccessToken(response.getToken());

        List<Permission> permissions = new ArrayList<>(rpt.getAuthorization().getPermissions());

        assertEquals(10, permissions.size());

        for (int i = 0; i < 10; i++) {
            assertEquals("Resource " + (i + 1), permissions.get(i).getResourceName());
        }

        request = new AuthorizationRequest();

        for (int i = 11; i <= 15; i++) {
            request.addPermission("Resource " + i);
        }

        request.setMetadata(metadata);
        request.setRpt(response.getToken());

        response = getAuthzClient(configFile).authorization("marta", "password").authorize(request);
        rpt = toAccessToken(response.getToken());

        permissions = new ArrayList<>(rpt.getAuthorization().getPermissions());

        assertEquals(10, permissions.size());

        for (int i = 0; i < 10; i++) {
            if (i < 5) {
                assertEquals("Resource " + (i + 11), permissions.get(i).getResourceName());
            } else {
                assertEquals("Resource " + (i - 4), permissions.get(i).getResourceName());
            }
        }

        request = new AuthorizationRequest();

        for (int i = 16; i <= 18; i++) {
            request.addPermission("Resource " + i);
        }

        request.setMetadata(metadata);
        request.setRpt(response.getToken());

        response = getAuthzClient(configFile).authorization("marta", "password").authorize(request);
        rpt = toAccessToken(response.getToken());

        permissions = new ArrayList<>(rpt.getAuthorization().getPermissions());

        assertEquals(10, permissions.size());
        assertEquals("Resource 16", permissions.get(0).getResourceName());
        assertEquals("Resource 17", permissions.get(1).getResourceName());
        assertEquals("Resource 18", permissions.get(2).getResourceName());
        assertEquals("Resource 11", permissions.get(3).getResourceName());
        assertEquals("Resource 12", permissions.get(4).getResourceName());
        assertEquals("Resource 13", permissions.get(5).getResourceName());
        assertEquals("Resource 14", permissions.get(6).getResourceName());
        assertEquals("Resource 15", permissions.get(7).getResourceName());
        assertEquals("Resource 1", permissions.get(8).getResourceName());
        assertEquals("Resource 2", permissions.get(9).getResourceName());

        request = new AuthorizationRequest();

        metadata.setLimit(5);
        request.setMetadata(metadata);
        request.setRpt(response.getToken());

        response = getAuthzClient(configFile).authorization("marta", "password").authorize(request);
        rpt = toAccessToken(response.getToken());

        permissions = new ArrayList<>(rpt.getAuthorization().getPermissions());

        assertEquals(5, permissions.size());
        assertEquals("Resource 16", permissions.get(0).getResourceName());
        assertEquals("Resource 17", permissions.get(1).getResourceName());
        assertEquals("Resource 18", permissions.get(2).getResourceName());
        assertEquals("Resource 11", permissions.get(3).getResourceName());
        assertEquals("Resource 12", permissions.get(4).getResourceName());
    }

    @Test
    public void testResourceServerAsAudience() throws Exception {
        testResourceServerAsAudience(
                TEST_CLIENT,
                RESOURCE_SERVER_TEST,
                AUTHZ_CLIENT_CONFIG);
    }

    @Test
    public void testResourceServerAsAudienceWithPairwiseClient() throws Exception {
        testResourceServerAsAudience(
                PAIRWISE_TEST_CLIENT,
                RESOURCE_SERVER_TEST,
                AUTHZ_CLIENT_CONFIG);
    }

    @Test
    public void testPairwiseResourceServerAsAudience() throws Exception {
        testResourceServerAsAudience(
                TEST_CLIENT,
                PAIRWISE_RESOURCE_SERVER_TEST,
                PAIRWISE_AUTHZ_CLIENT_CONFIG);
    }

    @Test
    public void testPairwiseResourceServerAsAudienceWithPairwiseClient() throws Exception {
        testResourceServerAsAudience(
                PAIRWISE_TEST_CLIENT,
                PAIRWISE_RESOURCE_SERVER_TEST,
                PAIRWISE_AUTHZ_CLIENT_CONFIG);
    }

    @Test
    public void testObtainAllEntitlements() throws Exception {
        ClientResource client = getClient(getRealm(), RESOURCE_SERVER_TEST);
        AuthorizationResource authorization = client.authorization();

        JSPolicyRepresentation policy = new JSPolicyRepresentation();

        policy.setName("Only Owner Policy");
        policy.setCode("if ($evaluation.getContext().getIdentity().getId() == $evaluation.getPermission().getResource().getOwner()) {$evaluation.grant();}");

        authorization.policies().js().create(policy).close();

        ResourceRepresentation resource = new ResourceRepresentation();

        resource.setName("Marta Resource");
        resource.setOwner("marta");
        resource.setOwnerManagedAccess(true);

        resource = authorization.resources().create(resource).readEntity(ResourceRepresentation.class);

        ResourcePermissionRepresentation permission = new ResourcePermissionRepresentation();

        permission.setName("Marta Resource Permission");
        permission.addResource(resource.getId());
        permission.addPolicy(policy.getName());

        authorization.permissions().resource().create(permission);

        assertTrue(hasPermission("marta", "password", resource.getId()));
        assertFalse(hasPermission("kolo", "password", resource.getId()));

        String accessToken = new OAuthClient().realm("authz-test").clientId(RESOURCE_SERVER_TEST).doGrantAccessTokenRequest("secret", "kolo", "password").getAccessToken();
        AuthzClient authzClient = getAuthzClient(AUTHZ_CLIENT_CONFIG);
        PermissionResponse permissionResponse = authzClient.protection().permission().create(new PermissionRequest(resource.getId()));
        AuthorizationRequest request = new AuthorizationRequest();

        request.setTicket(permissionResponse.getTicket());

        try {
            authzClient.authorization(accessToken).authorize(request);
        } catch (Exception ignore) {

        }

        List<PermissionTicketRepresentation> tickets = authzClient.protection().permission().findByResource(resource.getId());

        assertEquals(1, tickets.size());

        PermissionTicketRepresentation ticket = tickets.get(0);

        ticket.setGranted(true);

        authzClient.protection().permission().update(ticket);

        assertTrue(hasPermission("kolo", "password", resource.getId()));

        resource.addScope("Scope A");

        authorization.resources().resource(resource.getId()).update(resource);

        // the addition of a new scope still grants access to resource and any scope
        assertFalse(hasPermission("kolo", "password", resource.getId()));

        accessToken = new OAuthClient().realm("authz-test").clientId(RESOURCE_SERVER_TEST).doGrantAccessTokenRequest("secret", "kolo", "password").getAccessToken();
        permissionResponse = authzClient.protection().permission().create(new PermissionRequest(resource.getId(), "Scope A"));
        request = new AuthorizationRequest();

        request.setTicket(permissionResponse.getTicket());

        try {
            authzClient.authorization(accessToken).authorize(request);
        } catch (Exception ignore) {

        }

        tickets = authzClient.protection().permission().find(resource.getId(), "Scope A", null, null, false, false, null, null);

        assertEquals(1, tickets.size());

        ticket = tickets.get(0);

        ticket.setGranted(true);

        authzClient.protection().permission().update(ticket);

        assertTrue(hasPermission("kolo", "password", resource.getId(), "Scope A"));

        resource.addScope("Scope B");

        authorization.resources().resource(resource.getId()).update(resource);

        assertTrue(hasPermission("kolo", "password", resource.getId()));
        assertTrue(hasPermission("kolo", "password", resource.getId(), "Scope A"));
        assertFalse(hasPermission("kolo", "password", resource.getId(), "Scope B"));

        resource.setScopes(new HashSet<>());

        authorization.resources().resource(resource.getId()).update(resource);

        assertTrue(hasPermission("kolo", "password", resource.getId()));
        assertFalse(hasPermission("kolo", "password", resource.getId(), "Scope A"));
        assertFalse(hasPermission("kolo", "password", resource.getId(), "Scope B"));
    }

    @Test
    public void testObtainAllEntitlementsWithLimit() throws Exception {
        org.keycloak.authorization.client.resource.AuthorizationResource authorizationResource = getAuthzClient(AUTHZ_CLIENT_CONFIG).authorization("marta", "password");
        AuthorizationResponse response = authorizationResource.authorize();
        AccessToken accessToken = toAccessToken(response.getToken());
        Authorization authorization = accessToken.getAuthorization();

        assertTrue(authorization.getPermissions().size() >= 20);

        AuthorizationRequest request = new AuthorizationRequest();
        Metadata metadata = new Metadata();

        metadata.setLimit(10);

        request.setMetadata(metadata);

        response = authorizationResource.authorize(request);
        accessToken = toAccessToken(response.getToken());
        authorization = accessToken.getAuthorization();

        assertEquals(10, authorization.getPermissions().size());

        metadata.setLimit(1);

        request.setMetadata(metadata);

        response = authorizationResource.authorize(request);
        accessToken = toAccessToken(response.getToken());
        authorization = accessToken.getAuthorization();

        assertEquals(1, authorization.getPermissions().size());
    }

    @Test
    public void testObtainAllEntitlementsInvalidResource() throws Exception {
        ClientResource client = getClient(getRealm(), RESOURCE_SERVER_TEST);
        AuthorizationResource authorization = client.authorization();

        JSPolicyRepresentation policy = new JSPolicyRepresentation();

        policy.setName(KeycloakModelUtils.generateId());
        policy.setCode("$evaluation.grant();");

        authorization.policies().js().create(policy).close();

        ResourceRepresentation resource = new ResourceRepresentation();

        resource.setName("Sensors");
        resource.addScope("sensors:view", "sensors:update", "sensors:delete");

        resource = authorization.resources().create(resource).readEntity(ResourceRepresentation.class);

        ScopePermissionRepresentation permission = new ScopePermissionRepresentation();

        permission.setName("View Sensor");
        permission.addScope("sensors:view");
        permission.addPolicy(policy.getName());

        authorization.permissions().scope().create(permission);

        String accessToken = new OAuthClient().realm("authz-test").clientId(RESOURCE_SERVER_TEST).doGrantAccessTokenRequest("secret", "kolo", "password").getAccessToken();
        AuthzClient authzClient = getAuthzClient(AUTHZ_CLIENT_CONFIG);
        AuthorizationRequest request = new AuthorizationRequest();

        request.addPermission("Sensortest", "sensors:view");

        try {
            authzClient.authorization(accessToken).authorize(request);
            fail("resource is invalid");
        } catch (RuntimeException expected) {
            assertEquals(400, HttpResponseException.class.cast(expected.getCause()).getStatusCode());
            assertTrue(HttpResponseException.class.cast(expected.getCause()).toString().contains("invalid_resource"));
        }
    }

    @Test
    public void testObtainAllEntitlementsInvalidScope() throws Exception {
        ClientResource client = getClient(getRealm(), RESOURCE_SERVER_TEST);
        AuthorizationResource authorization = client.authorization();

        JSPolicyRepresentation policy = new JSPolicyRepresentation();

        policy.setName(KeycloakModelUtils.generateId());
        policy.setCode("$evaluation.grant();");

        authorization.policies().js().create(policy).close();

        ResourceRepresentation resource = new ResourceRepresentation();

        resource.setName(KeycloakModelUtils.generateId());
        resource.addScope("sensors:view", "sensors:update", "sensors:delete");

        resource = authorization.resources().create(resource).readEntity(ResourceRepresentation.class);

        ScopePermissionRepresentation permission = new ScopePermissionRepresentation();

        permission.setName(KeycloakModelUtils.generateId());
        permission.addScope("sensors:view");
        permission.addPolicy(policy.getName());

        authorization.permissions().scope().create(permission);

        String accessToken = new OAuthClient().realm("authz-test").clientId(RESOURCE_SERVER_TEST).doGrantAccessTokenRequest("secret", "kolo", "password").getAccessToken();
        AuthzClient authzClient = getAuthzClient(AUTHZ_CLIENT_CONFIG);
        AuthorizationRequest request = new AuthorizationRequest();

        request.addPermission(resource.getId(), "sensors:view_invalid");

        try {
            authzClient.authorization(accessToken).authorize(request);
            fail("scope is invalid");
        } catch (RuntimeException expected) {
            assertEquals(400, HttpResponseException.class.cast(expected.getCause()).getStatusCode());
            assertTrue(HttpResponseException.class.cast(expected.getCause()).toString().contains("invalid_scope"));
        }

        request = new AuthorizationRequest();

        request.addPermission(null, "sensors:view_invalid");

        try {
            authzClient.authorization(accessToken).authorize(request);
            fail("scope is invalid");
        } catch (RuntimeException expected) {
            assertEquals(400, HttpResponseException.class.cast(expected.getCause()).getStatusCode());
            assertTrue(HttpResponseException.class.cast(expected.getCause()).toString().contains("invalid_scope"));
        }
    }

    @Test
    public void testObtainAllEntitlementsForScope() throws Exception {
        ClientResource client = getClient(getRealm(), RESOURCE_SERVER_TEST);
        AuthorizationResource authorization = client.authorization();

        JSPolicyRepresentation policy = new JSPolicyRepresentation();

        policy.setName(KeycloakModelUtils.generateId());
        policy.setCode("$evaluation.grant();");

        authorization.policies().js().create(policy).close();

        Set<String> resourceIds = new HashSet<>();
        ResourceRepresentation resource = new ResourceRepresentation();

        resource.setName(KeycloakModelUtils.generateId());
        resource.addScope("sensors:view", "sensors:update", "sensors:delete");

        resourceIds.add(authorization.resources().create(resource).readEntity(ResourceRepresentation.class).getId());

        resource = new ResourceRepresentation();

        resource.setName(KeycloakModelUtils.generateId());
        resource.addScope("sensors:view", "sensors:update");

        resourceIds.add(authorization.resources().create(resource).readEntity(ResourceRepresentation.class).getId());

        ScopePermissionRepresentation permission = new ScopePermissionRepresentation();

        permission.setName(KeycloakModelUtils.generateId());
        permission.addScope("sensors:view", "sensors:update");
        permission.addPolicy(policy.getName());

        authorization.permissions().scope().create(permission);

        String accessToken = new OAuthClient().realm("authz-test").clientId(RESOURCE_SERVER_TEST).doGrantAccessTokenRequest("secret", "kolo", "password").getAccessToken();
        AuthzClient authzClient = getAuthzClient(AUTHZ_CLIENT_CONFIG);
        AuthorizationRequest request = new AuthorizationRequest();

        request.addPermission(null, "sensors:view");

        AuthorizationResponse response = authzClient.authorization(accessToken).authorize(request);
        assertNotNull(response.getToken());
        Collection<Permission> permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();
        assertEquals(2, permissions.size());

        for (Permission grantedPermission : permissions) {
            assertTrue(resourceIds.containsAll(Arrays.asList(grantedPermission.getResourceId())));
            assertEquals(1, grantedPermission.getScopes().size());
            assertTrue(grantedPermission.getScopes().containsAll(Arrays.asList("sensors:view")));
        }

        request.addPermission(null, "sensors:view", "sensors:update");

        response = authzClient.authorization(accessToken).authorize(request);
        assertNotNull(response.getToken());
        permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();
        assertEquals(2, permissions.size());

        for (Permission grantedPermission : permissions) {
            assertTrue(resourceIds.containsAll(Arrays.asList(grantedPermission.getResourceId())));
            assertEquals(2, grantedPermission.getScopes().size());
            assertTrue(grantedPermission.getScopes().containsAll(Arrays.asList("sensors:view", "sensors:update")));
        }

        request.addPermission(null, "sensors:view", "sensors:update", "sensors:delete");

        response = authzClient.authorization(accessToken).authorize(request);
        assertNotNull(response.getToken());
        permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();
        assertEquals(2, permissions.size());

        for (Permission grantedPermission : permissions) {
            assertTrue(resourceIds.containsAll(Arrays.asList(grantedPermission.getResourceId())));
            assertEquals(2, grantedPermission.getScopes().size());
            assertTrue(grantedPermission.getScopes().containsAll(Arrays.asList("sensors:view", "sensors:update")));
        }

        request = new AuthorizationRequest();

        request.addPermission(null, "sensors:view");
        request.addPermission(null, "sensors:update");

        response = authzClient.authorization(accessToken).authorize(request);
        assertNotNull(response.getToken());
        permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();
        assertEquals(2, permissions.size());

        for (Permission grantedPermission : permissions) {
            assertTrue(resourceIds.containsAll(Arrays.asList(grantedPermission.getResourceId())));
            assertEquals(2, grantedPermission.getScopes().size());
            assertTrue(grantedPermission.getScopes().containsAll(Arrays.asList("sensors:view", "sensors:update")));
        }
    }

    @Test
    public void testObtainAllEntitlementsForResource() throws Exception {
        ClientResource client = getClient(getRealm(), RESOURCE_SERVER_TEST);
        AuthorizationResource authorization = client.authorization();

        JSPolicyRepresentation policy = new JSPolicyRepresentation();

        policy.setName(KeycloakModelUtils.generateId());
        policy.setCode("$evaluation.grant();");

        authorization.policies().js().create(policy).close();

        ResourceRepresentation resource = new ResourceRepresentation();

        resource.setName(KeycloakModelUtils.generateId());
        resource.addScope("scope:view", "scope:update", "scope:delete");

        resource = authorization.resources().create(resource).readEntity(ResourceRepresentation.class);

        ResourcePermissionRepresentation permission = new ResourcePermissionRepresentation();

        permission.setName(KeycloakModelUtils.generateId());
        permission.addResource(resource.getId());
        permission.addPolicy(policy.getName());

        authorization.permissions().resource().create(permission);

        String accessToken = new OAuthClient().realm("authz-test").clientId(RESOURCE_SERVER_TEST).doGrantAccessTokenRequest("secret", "kolo", "password").getAccessToken();
        AuthzClient authzClient = getAuthzClient(AUTHZ_CLIENT_CONFIG);
        AuthorizationRequest request = new AuthorizationRequest();

        request.addPermission(null, "scope:view", "scope:update", "scope:delete");

        AuthorizationResponse response = authzClient.authorization(accessToken).authorize(request);
        assertNotNull(response.getToken());
        Collection<Permission> permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();
        assertEquals(1, permissions.size());

        for (Permission grantedPermission : permissions) {
            assertEquals(resource.getId(), grantedPermission.getResourceId());
            assertEquals(3, grantedPermission.getScopes().size());
            assertTrue(grantedPermission.getScopes().containsAll(Arrays.asList("scope:view")));
        }

        resource.setScopes(new HashSet<>());
        resource.addScope("scope:view", "scope:update");

        authorization.resources().resource(resource.getId()).update(resource);

        request = new AuthorizationRequest();

        request.addPermission(null, "scope:view", "scope:update", "scope:delete");

        response = authzClient.authorization(accessToken).authorize(request);
        assertNotNull(response.getToken());
        permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();
        assertEquals(1, permissions.size());

        for (Permission grantedPermission : permissions) {
            assertEquals(resource.getId(), grantedPermission.getResourceId());
            assertEquals(2, grantedPermission.getScopes().size());
            assertTrue(grantedPermission.getScopes().containsAll(Arrays.asList("scope:view", "scope:update")));
        }

        request = new AuthorizationRequest();

        request.addPermission(resource.getId(), "scope:view", "scope:update", "scope:delete");

        for (Permission grantedPermission : permissions) {
            assertEquals(resource.getId(), grantedPermission.getResourceId());
            assertEquals(2, grantedPermission.getScopes().size());
            assertTrue(grantedPermission.getScopes().containsAll(Arrays.asList("scope:view", "scope:update")));
        }
    }

    @Test
    public void testOverridePermission() throws Exception {
        ClientResource client = getClient(getRealm(), RESOURCE_SERVER_TEST);
        AuthorizationResource authorization = client.authorization();
        JSPolicyRepresentation onlyOwnerPolicy = createOnlyOwnerPolicy();

        authorization.policies().js().create(onlyOwnerPolicy).close();

        ResourceRepresentation typedResource = new ResourceRepresentation();

        typedResource.setType("resource");
        typedResource.setName(KeycloakModelUtils.generateId());
        typedResource.addScope("read", "update");

        typedResource = authorization.resources().create(typedResource).readEntity(ResourceRepresentation.class);

        ResourcePermissionRepresentation typedResourcePermission = new ResourcePermissionRepresentation();

        typedResourcePermission.setName(KeycloakModelUtils.generateId());
        typedResourcePermission.setResourceType("resource");
        typedResourcePermission.addPolicy(onlyOwnerPolicy.getName());

        typedResourcePermission = authorization.permissions().resource().create(typedResourcePermission).readEntity(ResourcePermissionRepresentation.class);

        ResourceRepresentation martaResource = new ResourceRepresentation();

        martaResource.setType("resource");
        martaResource.setName(KeycloakModelUtils.generateId());
        martaResource.addScope("read", "update");
        martaResource.setOwner("marta");

        martaResource = authorization.resources().create(martaResource).readEntity(ResourceRepresentation.class);

        String accessToken = new OAuthClient().realm("authz-test").clientId(RESOURCE_SERVER_TEST).doGrantAccessTokenRequest("secret", "marta", "password").getAccessToken();
        AuthzClient authzClient = getAuthzClient(AUTHZ_CLIENT_CONFIG);
        AuthorizationRequest request = new AuthorizationRequest();

        request.addPermission(martaResource.getName());

        // marta can access her resource
        AuthorizationResponse response = authzClient.authorization(accessToken).authorize(request);
        assertNotNull(response.getToken());
        Collection<Permission> permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();
        assertEquals(1, permissions.size());

        for (Permission grantedPermission : permissions) {
            assertEquals(martaResource.getName(), grantedPermission.getResourceName());
            Set<String> scopes = grantedPermission.getScopes();
            assertEquals(2, scopes.size());
            assertThat(scopes, Matchers.containsInAnyOrder("read", "update"));
        }

        accessToken = new OAuthClient().realm("authz-test").clientId(RESOURCE_SERVER_TEST).doGrantAccessTokenRequest("secret", "kolo", "password").getAccessToken();
        authzClient = getAuthzClient(AUTHZ_CLIENT_CONFIG);

        request = new AuthorizationRequest();

        request.addPermission(martaResource.getId());

        try {
            authzClient.authorization(accessToken).authorize(request);
            fail("kolo can not access marta resource");
        } catch (RuntimeException expected) {
            assertEquals(403, HttpResponseException.class.cast(expected.getCause()).getStatusCode());
            assertTrue(HttpResponseException.class.cast(expected.getCause()).toString().contains("access_denied"));
        }

        UserPolicyRepresentation onlyKoloPolicy = new UserPolicyRepresentation();

        onlyKoloPolicy.setName(KeycloakModelUtils.generateId());
        onlyKoloPolicy.addUser("kolo");

        authorization.policies().user().create(onlyKoloPolicy);

        ResourcePermissionRepresentation martaResourcePermission = new ResourcePermissionRepresentation();

        martaResourcePermission.setName(KeycloakModelUtils.generateId());
        martaResourcePermission.addResource(martaResource.getId());
        martaResourcePermission.addPolicy(onlyKoloPolicy.getName());

        martaResourcePermission = authorization.permissions().resource().create(martaResourcePermission).readEntity(ResourcePermissionRepresentation.class);

        response = authzClient.authorization(accessToken).authorize(request);
        assertNotNull(response.getToken());
        permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();
        assertEquals(1, permissions.size());

        for (Permission grantedPermission : permissions) {
            assertEquals(martaResource.getName(), grantedPermission.getResourceName());
            Set<String> scopes = grantedPermission.getScopes();
            assertEquals(2, scopes.size());
            assertThat(scopes, Matchers.containsInAnyOrder("read", "update"));
        }

        typedResourcePermission.setResourceType(null);
        typedResourcePermission.addResource(typedResource.getName());

        authorization.permissions().resource().findById(typedResourcePermission.getId()).update(typedResourcePermission);

        // now kolo can access marta's resources, last permission is overriding policies from typed resource
        response = authzClient.authorization(accessToken).authorize(request);
        assertNotNull(response.getToken());
        permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();
        assertEquals(1, permissions.size());

        for (Permission grantedPermission : permissions) {
            assertEquals(martaResource.getName(), grantedPermission.getResourceName());
            Set<String> scopes = grantedPermission.getScopes();
            assertEquals(2, scopes.size());
            assertThat(scopes, Matchers.containsInAnyOrder("read", "update"));
        }

        ScopePermissionRepresentation martaResourceUpdatePermission = new ScopePermissionRepresentation();

        martaResourceUpdatePermission.setName(KeycloakModelUtils.generateId());
        martaResourceUpdatePermission.addResource(martaResource.getId());
        martaResourceUpdatePermission.addScope("update");
        martaResourceUpdatePermission.addPolicy(onlyOwnerPolicy.getName());

        martaResourceUpdatePermission = authorization.permissions().scope().create(martaResourceUpdatePermission).readEntity(ScopePermissionRepresentation.class);

        // now kolo can only read, but not update
        response = authzClient.authorization(accessToken).authorize(request);
        assertNotNull(response.getToken());
        permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();
        assertEquals(1, permissions.size());

        for (Permission grantedPermission : permissions) {
            assertEquals(martaResource.getName(), grantedPermission.getResourceName());
            Set<String> scopes = grantedPermission.getScopes();
            assertEquals(1, scopes.size());
            assertThat(scopes, Matchers.containsInAnyOrder("read"));
        }

        authorization.permissions().resource().findById(martaResourcePermission.getId()).remove();

        try {
            // after removing permission to marta resource, kolo can not access any scope in the resource
            authzClient.authorization(accessToken).authorize(request);
            fail("kolo can not access marta resource");
        } catch (RuntimeException expected) {
            assertEquals(403, HttpResponseException.class.cast(expected.getCause()).getStatusCode());
            assertTrue(HttpResponseException.class.cast(expected.getCause()).toString().contains("access_denied"));
        }

        martaResourceUpdatePermission.addPolicy(onlyKoloPolicy.getName());
        martaResourceUpdatePermission.setDecisionStrategy(DecisionStrategy.AFFIRMATIVE);

        authorization.permissions().scope().findById(martaResourceUpdatePermission.getId()).update(martaResourceUpdatePermission);

        // now kolo can access because update permission changed to allow him to access the resource using an affirmative strategy
        response = authzClient.authorization(accessToken).authorize(request);
        assertNotNull(response.getToken());
        permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();
        assertEquals(1, permissions.size());

        for (Permission grantedPermission : permissions) {
            assertEquals(martaResource.getName(), grantedPermission.getResourceName());
            Set<String> scopes = grantedPermission.getScopes();
            assertEquals(1, scopes.size());
            assertThat(scopes, Matchers.containsInAnyOrder("update"));
        }

        accessToken = new OAuthClient().realm("authz-test").clientId(RESOURCE_SERVER_TEST).doGrantAccessTokenRequest("secret", "marta", "password").getAccessToken();

        // marta can still access her resource
        response = authzClient.authorization(accessToken).authorize(request);
        assertNotNull(response.getToken());
        permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();
        assertEquals(1, permissions.size());

        for (Permission grantedPermission : permissions) {
            assertEquals(martaResource.getName(), grantedPermission.getResourceName());
            Set<String> scopes = grantedPermission.getScopes();
            assertEquals(2, scopes.size());
            assertThat(scopes, Matchers.containsInAnyOrder("update", "read"));
        }

        authorization.permissions().scope().findById(martaResourceUpdatePermission.getId()).remove();
        accessToken = new OAuthClient().realm("authz-test").clientId(RESOURCE_SERVER_TEST).doGrantAccessTokenRequest("secret", "kolo", "password").getAccessToken();

        try {
            // back to original setup, permissions not granted by the type resource
            authzClient.authorization(accessToken).authorize(request);
            fail("kolo can not access marta resource");
        } catch (RuntimeException expected) {
            assertEquals(403, HttpResponseException.class.cast(expected.getCause()).getStatusCode());
            assertTrue(HttpResponseException.class.cast(expected.getCause()).toString().contains("access_denied"));
        }
    }

    @NotNull
    private JSPolicyRepresentation createOnlyOwnerPolicy() {
        JSPolicyRepresentation onlyOwnerPolicy = new JSPolicyRepresentation();

        onlyOwnerPolicy.setName(KeycloakModelUtils.generateId());
        onlyOwnerPolicy.setCode("var context = $evaluation.getContext();\n" +
                "var identity = context.getIdentity();\n" +
                "var permission = $evaluation.getPermission();\n" +
                "var resource = permission.getResource();\n" +
                "\n" +
                "if (resource) {\n" +
                "    if (resource.owner == identity.id) {\n" +
                "        $evaluation.grant();\n" +
                "    }\n" +
                "}");

        return onlyOwnerPolicy;
    }

    @Test
    public void testPermissionsWithResourceAttributes() throws Exception {
        ClientResource client = getClient(getRealm(), RESOURCE_SERVER_TEST);
        AuthorizationResource authorization = client.authorization();
        JSPolicyRepresentation onlyPublicResourcesPolicy = new JSPolicyRepresentation();

        onlyPublicResourcesPolicy.setName(KeycloakModelUtils.generateId());
        onlyPublicResourcesPolicy.setCode("var createPermission = $evaluation.getPermission();\n" +
                "var resource = createPermission.getResource();\n" +
                "\n" +
                "if (resource) {\n" +
                "    var attributes = resource.getAttributes();\n" +
                "    var visibility = attributes.get('visibility');\n" +
                "    \n" +
                "    if (visibility && \"private\".equals(visibility.get(0))) {\n" +
                "        $evaluation.deny();\n" +
                "      } else {\n" +
                "        $evaluation.grant();\n" +
                "    }\n" +
                "}");

        authorization.policies().js().create(onlyPublicResourcesPolicy).close();

        JSPolicyRepresentation onlyOwnerPolicy = createOnlyOwnerPolicy();

        authorization.policies().js().create(onlyOwnerPolicy).close();

        ResourceRepresentation typedResource = new ResourceRepresentation();

        typedResource.setType("resource");
        typedResource.setName(KeycloakModelUtils.generateId());

        typedResource = authorization.resources().create(typedResource).readEntity(ResourceRepresentation.class);

        ResourceRepresentation userResource = new ResourceRepresentation();

        userResource.setName(KeycloakModelUtils.generateId());
        userResource.setType("resource");
        userResource.setOwner("marta");
        Map<String, List<String>> attributes = new HashMap<>();
        attributes.put("visibility", Arrays.asList("private"));
        userResource.setAttributes(attributes);

        userResource = authorization.resources().create(userResource).readEntity(ResourceRepresentation.class);

        ResourcePermissionRepresentation typedResourcePermission = new ResourcePermissionRepresentation();

        typedResourcePermission.setName(KeycloakModelUtils.generateId());
        typedResourcePermission.setResourceType("resource");
        typedResourcePermission.addPolicy(onlyPublicResourcesPolicy.getName());

        typedResourcePermission = authorization.permissions().resource().create(typedResourcePermission).readEntity(ResourcePermissionRepresentation.class);

        // marta can access any public resource
        AuthzClient authzClient = getAuthzClient(AUTHZ_CLIENT_CONFIG);
        AuthorizationRequest request = new AuthorizationRequest();

        request.addPermission(typedResource.getId());
        request.addPermission(userResource.getId());

        AuthorizationResponse response = authzClient.authorization("marta", "password").authorize(request);
        assertNotNull(response.getToken());
        Collection<Permission> permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();
        assertEquals(1, permissions.size());

        for (Permission grantedPermission : permissions) {
            assertEquals(typedResource.getName(), grantedPermission.getResourceName());
        }

        typedResourcePermission.addPolicy(onlyOwnerPolicy.getName());
        typedResourcePermission.setDecisionStrategy(DecisionStrategy.AFFIRMATIVE);

        authorization.permissions().resource().findById(typedResourcePermission.getId()).update(typedResourcePermission);

        response = authzClient.authorization("marta", "password").authorize(request);
        assertNotNull(response.getToken());
        permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();
        assertEquals(2, permissions.size());

        for (Permission grantedPermission : permissions) {
            assertThat(Arrays.asList(typedResource.getName(), userResource.getName()), Matchers.hasItem(grantedPermission.getResourceName()));
        }

        typedResource.setAttributes(attributes);

        authorization.resources().resource(typedResource.getId()).update(typedResource);

        response = authzClient.authorization("marta", "password").authorize(request);
        assertNotNull(response.getToken());
        permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();
        assertEquals(1, permissions.size());

        for (Permission grantedPermission : permissions) {
            assertThat(userResource.getName(), Matchers.equalTo(grantedPermission.getResourceName()));
        }

        userResource.addScope("create", "read");
        authorization.resources().resource(userResource.getId()).update(userResource);

        typedResource.addScope("create", "read");
        authorization.resources().resource(typedResource.getId()).update(typedResource);

        ScopePermissionRepresentation createPermission = new ScopePermissionRepresentation();

        createPermission.setName(KeycloakModelUtils.generateId());
        createPermission.addScope("create");
        createPermission.addPolicy(onlyPublicResourcesPolicy.getName());

        authorization.permissions().scope().create(createPermission);

        response = authzClient.authorization("marta", "password").authorize(request);
        assertNotNull(response.getToken());
        permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();
        assertEquals(1, permissions.size());

        for (Permission grantedPermission : permissions) {
            assertThat(userResource.getName(), Matchers.equalTo(grantedPermission.getResourceName()));
            assertThat(grantedPermission.getScopes(), Matchers.not(Matchers.hasItem("create")));
        }

        typedResource.setAttributes(new HashMap<>());

        authorization.resources().resource(typedResource.getId()).update(typedResource);

        response = authzClient.authorization("marta", "password").authorize();
        assertNotNull(response.getToken());
        permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();

        for (Permission grantedPermission : permissions) {
            if (grantedPermission.getResourceName().equals(userResource.getName())) {
                assertThat(grantedPermission.getScopes(), Matchers.not(Matchers.hasItem("create")));
            } else if (grantedPermission.getResourceName().equals(typedResource.getName())) {
                assertThat(grantedPermission.getScopes(), Matchers.containsInAnyOrder("create", "read"));
            }
        }

        request = new AuthorizationRequest();

        request.addPermission(typedResource.getId());
        request.addPermission(userResource.getId());

        response = authzClient.authorization("marta", "password").authorize(request);
        assertNotNull(response.getToken());
        permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();

        for (Permission grantedPermission : permissions) {
            if (grantedPermission.getResourceName().equals(userResource.getName())) {
                assertThat(grantedPermission.getScopes(), Matchers.not(Matchers.hasItem("create")));
            } else if (grantedPermission.getResourceName().equals(typedResource.getName())) {
                assertThat(grantedPermission.getScopes(), Matchers.containsInAnyOrder("create", "read"));
            }
        }

        request = new AuthorizationRequest();

        request.addPermission(userResource.getId());
        request.addPermission(typedResource.getId());

        response = authzClient.authorization("marta", "password").authorize(request);
        assertNotNull(response.getToken());
        permissions = toAccessToken(response.getToken()).getAuthorization().getPermissions();

        for (Permission grantedPermission : permissions) {
            if (grantedPermission.getResourceName().equals(userResource.getName())) {
                assertThat(grantedPermission.getScopes(), Matchers.not(Matchers.hasItem("create")));
            } else if (grantedPermission.getResourceName().equals(typedResource.getName())) {
                assertThat(grantedPermission.getScopes(), Matchers.containsInAnyOrder("create", "read"));
            }
        }
    }

    @Test
    public void testOfflineRequestingPartyToken() throws Exception {
        ClientResource client = getClient(getRealm(), RESOURCE_SERVER_TEST);
        AuthorizationResource authorization = client.authorization();

        JSPolicyRepresentation policy = new JSPolicyRepresentation();

        policy.setName(KeycloakModelUtils.generateId());
        policy.setCode("$evaluation.grant();");

        authorization.policies().js().create(policy).close();

        ResourceRepresentation resource = new ResourceRepresentation();

        resource.setName("Sensors");
        resource.addScope("sensors:view", "sensors:update", "sensors:delete");

        resource = authorization.resources().create(resource).readEntity(ResourceRepresentation.class);

        ScopePermissionRepresentation permission = new ScopePermissionRepresentation();

        permission.setName("View Sensor");
        permission.addScope("sensors:view");
        permission.addPolicy(policy.getName());

        authorization.permissions().scope().create(permission);

        String accessToken = new OAuthClient().realm("authz-test").clientId(RESOURCE_SERVER_TEST).scope("offline_access").doGrantAccessTokenRequest("secret", "offlineuser", "password").getAccessToken();
        AuthzClient authzClient = getAuthzClient(AUTHZ_CLIENT_CONFIG);
        AccessTokenResponse response = authzClient.authorization(accessToken).authorize();
        assertNotNull(response.getToken());

        controller.stop(suiteContext.getAuthServerInfo().getQualifier());
        controller.start(suiteContext.getAuthServerInfo().getQualifier());
        reconnectAdminClient();

        TokenIntrospectionResponse introspectionResponse = authzClient.protection().introspectRequestingPartyToken(response.getToken());

        assertTrue(introspectionResponse.getActive());
        assertFalse(introspectionResponse.getPermissions().isEmpty());

        response = authzClient.authorization(accessToken).authorize();
        assertNotNull(response.getToken());
    }

    private void testRptRequestWithResourceName(String configFile) {
        Metadata metadata = new Metadata();

        metadata.setIncludeResourceName(true);

        assertResponse(metadata, () -> getAuthzClient(configFile).authorization("marta", "password").authorize());

        AuthorizationRequest request = new AuthorizationRequest();

        request.setMetadata(metadata);
        request.addPermission("Resource 13");

        assertResponse(metadata, () -> getAuthzClient(configFile).authorization("marta", "password").authorize(request));

        request.setMetadata(null);

        assertResponse(metadata, () -> getAuthzClient(configFile).authorization("marta", "password").authorize(request));
    }

    private void testResourceServerAsAudience(String testClientId, String resourceServerClientId, String configFile) throws Exception {
        AuthorizationRequest request = new AuthorizationRequest();

        request.addPermission("Resource 1");

        String accessToken = new OAuthClient().realm("authz-test").clientId(testClientId).doGrantAccessTokenRequest("secret", "marta", "password").getAccessToken();
        AuthorizationResponse response = getAuthzClient(configFile).authorization(accessToken).authorize(request);
        AccessToken rpt = toAccessToken(response.getToken());

        assertEquals(resourceServerClientId, rpt.getAudience()[0]);
    }

    private boolean hasPermission(String userName, String password, String resourceId, String... scopeIds) throws Exception {
        String accessToken = new OAuthClient().realm("authz-test").clientId(RESOURCE_SERVER_TEST).doGrantAccessTokenRequest("secret", userName, password).getAccessToken();
        AuthorizationResponse response = getAuthzClient(AUTHZ_CLIENT_CONFIG).authorization(accessToken).authorize(new AuthorizationRequest());
        AccessToken rpt = toAccessToken(response.getToken());
        Authorization authz = rpt.getAuthorization();
        Collection<Permission> permissions = authz.getPermissions();

        assertNotNull(permissions);
        assertFalse(permissions.isEmpty());

        for (Permission grantedPermission : permissions) {
            if (grantedPermission.getResourceId().equals(resourceId)) {
                return scopeIds == null || scopeIds.length == 0 || grantedPermission.getScopes().containsAll(Arrays.asList(scopeIds));
            }
        }

        return false;
    }

    private boolean hasPermission(String userName, String password, String resourceId) throws Exception {
        return hasPermission(userName, password, resourceId, null);
    }

    private void assertResponse(Metadata metadata, Supplier<AuthorizationResponse> responseSupplier) {
        AccessToken.Authorization authorization = toAccessToken(responseSupplier.get().getToken()).getAuthorization();

        Collection<Permission> permissions = authorization.getPermissions();

        assertNotNull(permissions);
        assertFalse(permissions.isEmpty());

        for (Permission permission : permissions) {
            if (metadata.getIncludeResourceName()) {
                assertNotNull(permission.getResourceName());
            } else {
                assertNull(permission.getResourceName());
            }
        }
    }

    private RealmResource getRealm() throws Exception {
        return adminClient.realm("authz-test");
    }

    private ClientResource getClient(RealmResource realm, String clientId) {
        ClientsResource clients = realm.clients();
        return clients.findByClientId(clientId).stream().map(representation -> clients.get(representation.getId())).findFirst().orElseThrow(() -> new RuntimeException("Expected client [resource-server-test]"));
    }

    private AuthzClient getAuthzClient(String configFile) {
        if (authzClient == null) {
            try {
                authzClient = AuthzClient.create(JsonSerialization.readValue(getClass().getResourceAsStream("/authorization-test/" + configFile), Configuration.class));
            } catch (IOException cause) {
                throw new RuntimeException("Failed to create authz client", cause);
            }
        }

        return authzClient;
    }

    private void configureAuthorization(String clientId) throws Exception {
        ClientResource client = getClient(getRealm(), clientId);
        AuthorizationResource authorization = client.authorization();

        JSPolicyRepresentation policy = new JSPolicyRepresentation();

        policy.setName("Default Policy");
        policy.setCode("$evaluation.grant();");

        authorization.policies().js().create(policy).close();

        for (int i = 1; i <= 20; i++) {
            ResourceRepresentation resource = new ResourceRepresentation("Resource " + i);

            authorization.resources().create(resource).close();

            ResourcePermissionRepresentation permission = new ResourcePermissionRepresentation();

            permission.setName(resource.getName() + " Permission");
            permission.addResource(resource.getName());
            permission.addPolicy(policy.getName());

            authorization.permissions().resource().create(permission).close();
        }
    }

    private void removeAuthorization(String clientId) throws Exception {
        ClientResource client = getClient(getRealm(), clientId);
        ClientRepresentation representation = client.toRepresentation();

        representation.setAuthorizationServicesEnabled(false);

        client.update(representation);

        representation.setAuthorizationServicesEnabled(true);

        client.update(representation);
    }
}
