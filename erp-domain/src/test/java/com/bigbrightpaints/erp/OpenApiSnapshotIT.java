package com.bigbrightpaints.erp;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "erp.security.swagger-public=true")
public class OpenApiSnapshotIT extends AbstractIntegrationTest {

    private static final String SNAPSHOT_VERIFY_PROPERTY = "erp.openapi.snapshot.verify";
    private static final String SNAPSHOT_VERIFY_ENV = "ERP_OPENAPI_SNAPSHOT_VERIFY";
    private static final String SNAPSHOT_REFRESH_PROPERTY = "erp.openapi.snapshot.refresh";
    private static final String SNAPSHOT_REFRESH_ENV = "ERP_OPENAPI_SNAPSHOT_REFRESH";
    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Autowired
    private TestRestTemplate rest;

    @Test
    void auth_and_admin_contract_paths_preserve_expected_response_shapes() throws IOException {
        JsonNode root = fetchCurrentSpecNode();

        assertOperationContract(root, "/api/v1/auth/login", "post",
                "#/components/schemas/LoginRequest", "200", "#/components/schemas/AuthResponse");
        assertOperationContract(root, "/api/v1/auth/refresh-token", "post",
                "#/components/schemas/RefreshTokenRequest", "200", "#/components/schemas/AuthResponse");
        assertOperationContract(root, "/api/v1/auth/logout", "post",
                null, "204", null);
        assertOperationContract(root, "/api/v1/auth/me", "get",
                null, "200", "#/components/schemas/ApiResponseMeResponse");
        assertOperationContract(root, "/api/v1/auth/password/change", "post",
                "#/components/schemas/ChangePasswordRequest", "200", "#/components/schemas/ApiResponseString");
        assertOperationContract(root, "/api/v1/auth/password/forgot", "post",
                "#/components/schemas/ForgotPasswordRequest", "200", "#/components/schemas/ApiResponseString");
        assertOperationContract(root, "/api/v1/auth/password/forgot/superadmin", "post",
                "#/components/schemas/ForgotPasswordRequest", "410", "#/components/schemas/ApiResponseMapStringString");
        assertThat(root.path("paths").path("/api/v1/auth/password/forgot/superadmin").path("post").path("deprecated").asBoolean())
                .withFailMessage("Expected POST /api/v1/auth/password/forgot/superadmin to stay published as deprecated")
                .isTrue();
        assertOperationContract(root, "/api/v1/auth/password/reset", "post",
                "#/components/schemas/ResetPasswordRequest", "200", "#/components/schemas/ApiResponseString");
        assertOperationContract(root, "/api/v1/companies/{id}/support/admin-password-reset", "post",
                "#/components/schemas/CompanyAdminPasswordResetRequest", "200",
                "#/components/schemas/ApiResponseCompanyAdminCredentialResetDto");

        assertOperationContract(root, "/api/v1/admin/settings", "get",
                null, "200", "#/components/schemas/ApiResponseSystemSettingsDto");
        assertOperationContract(root, "/api/v1/admin/settings", "put",
                "#/components/schemas/SystemSettingsUpdateRequest", "200", "#/components/schemas/ApiResponseSystemSettingsDto");
        assertOperationContract(root, "/api/v1/admin/tenant-runtime/metrics", "get",
                null, "200", "#/components/schemas/ApiResponseTenantRuntimeMetricsDto");
        assertOperationContract(root, "/api/v1/admin/tenant-runtime/policy", "put",
                "#/components/schemas/TenantRuntimePolicyUpdateRequest", "200", "#/components/schemas/ApiResponseTenantRuntimeMetricsDto");
        assertOperationContract(root, "/api/v1/admin/users/{userId}/force-reset-password", "post",
                null, "200", "#/components/schemas/ApiResponseString");
        assertOperationContract(root, "/api/v1/admin/users/{userId}/status", "put",
                "#/components/schemas/UpdateUserStatusRequest", "200", "#/components/schemas/ApiResponseUserDto");
        assertOperationContract(root, "/api/v1/admin/users/{id}/suspend", "patch",
                null, "204", null);
        assertOperationContract(root, "/api/v1/admin/users/{id}/unsuspend", "patch",
                null, "204", null);
        assertOperationContract(root, "/api/v1/admin/users/{id}/mfa/disable", "patch",
                null, "204", null);
        assertOperationContract(root, "/api/v1/admin/users/{id}", "delete",
                null, "204", null);
    }

    @Test
    void openapi_snapshot_matches_repository_contract() throws IOException {
        Path openApiSnapshotPath = resolveRepoRoot().resolve("openapi.json");
        String currentSpec = canonicalizeJson(fetchCurrentSpecNode().toString());
        if (refreshRequested()) {
            assertThat(verifyRequested())
                    .withFailMessage("Refresh requires verify mode. Set -D%s=true (or %s=true) together with "
                                    + "-D%s=true (or %s=true).",
                            SNAPSHOT_VERIFY_PROPERTY,
                            SNAPSHOT_VERIFY_ENV,
                            SNAPSHOT_REFRESH_PROPERTY,
                            SNAPSHOT_REFRESH_ENV)
                    .isTrue();
            Files.writeString(openApiSnapshotPath, currentSpec, StandardCharsets.UTF_8);
            return;
        }

        assertThat(Files.exists(openApiSnapshotPath))
                .withFailMessage("Missing OpenAPI snapshot at %s. Remediation: rerun intentionally with -D%s=true "
                                + "or %s=true (with -D%s=true or %s=true) to generate it.",
                        openApiSnapshotPath,
                        SNAPSHOT_REFRESH_PROPERTY,
                        SNAPSHOT_REFRESH_ENV,
                        SNAPSHOT_VERIFY_PROPERTY,
                        SNAPSHOT_VERIFY_ENV)
                .isTrue();

        String snapshotSpec = canonicalizeJson(Files.readString(openApiSnapshotPath, StandardCharsets.UTF_8));
        String currentSpecHash = sha256Hex(currentSpec);
        String snapshotSpecHash = sha256Hex(snapshotSpec);
        List<String> currentOps = extractOperationSignatures(currentSpec);
        List<String> snapshotOps = extractOperationSignatures(snapshotSpec);

        assertThat(snapshotOps)
                .withFailMessage("OpenAPI snapshot at %s has no operations. Refresh snapshot intentionally with "
                                + "-D%s=true (or %s=true) and -D%s=true (or %s=true).",
                        openApiSnapshotPath,
                        SNAPSHOT_VERIFY_PROPERTY,
                        SNAPSHOT_VERIFY_ENV,
                        SNAPSHOT_REFRESH_PROPERTY,
                        SNAPSHOT_REFRESH_ENV)
                .isNotEmpty();

        assertThat(currentOps)
                .withFailMessage("OpenAPI breaking operation drift detected at %s. currentOps=%d snapshotOps=%d "
                                + "(delta=%d) currentHash=%s snapshotHash=%s. Snapshot operations must remain present "
                                + "in the runtime contract. For full parity (including additive drift), rerun with "
                                + "-D%s=true (or %s=true) and refresh using -D%s=true (or %s=true).",
                        openApiSnapshotPath,
                        currentOps.size(),
                        snapshotOps.size(),
                        currentOps.size() - snapshotOps.size(),
                        currentSpecHash,
                        snapshotSpecHash,
                        SNAPSHOT_VERIFY_PROPERTY,
                        SNAPSHOT_VERIFY_ENV,
                        SNAPSHOT_REFRESH_PROPERTY,
                        SNAPSHOT_REFRESH_ENV)
                .containsAll(snapshotOps);

        if (!verifyRequested()) {
            return;
        }

        assertThat(currentSpec)
                .withFailMessage("OpenAPI snapshot drift detected at %s. Verify mode is non-mutating unless refresh is enabled. "
                                + "Parity signal (sha256) current=%s snapshot=%s. "
                                + "Remediation: rerun intentionally with -D%s=true (or %s=true) and -D%s=true "
                                + "(or %s=true), then commit updated openapi.json.",
                        openApiSnapshotPath,
                        currentSpecHash,
                        snapshotSpecHash,
                        SNAPSHOT_VERIFY_PROPERTY,
                        SNAPSHOT_VERIFY_ENV,
                        SNAPSHOT_REFRESH_PROPERTY,
                        SNAPSHOT_REFRESH_ENV)
                .isEqualTo(snapshotSpec);
    }

    private static boolean verifyRequested() {
        return Boolean.parseBoolean(System.getProperty(
                SNAPSHOT_VERIFY_PROPERTY,
                System.getenv().getOrDefault(SNAPSHOT_VERIFY_ENV, "false")));
    }

    private static boolean refreshRequested() {
        return Boolean.parseBoolean(System.getProperty(
                SNAPSHOT_REFRESH_PROPERTY,
                System.getenv().getOrDefault(SNAPSHOT_REFRESH_ENV, "false")));
    }

    private static Path resolveRepoRoot() {
        Path moduleRoot = Path.of("").toAbsolutePath().normalize();
        if (moduleRoot.getFileName() != null && "erp-domain".equals(moduleRoot.getFileName().toString())) {
            Path parent = moduleRoot.getParent();
            if (parent != null) {
                return parent;
            }
        }
        return moduleRoot;
    }

    private static String canonicalizeJson(String spec) throws IOException {
        JsonNode parsedSpec = CANONICAL_JSON.readTree(spec);
        return CANONICAL_JSON.writeValueAsString(canonicalizeNode(parsedSpec));
    }

    private static List<String> extractOperationSignatures(String spec) throws IOException {
        JsonNode root = CANONICAL_JSON.readTree(spec);
        JsonNode paths = root.get("paths");
        List<String> operations = new ArrayList<>();
        if (paths == null || !paths.isObject()) {
            return operations;
        }
        paths.fields().forEachRemaining(pathEntry -> {
            JsonNode methods = pathEntry.getValue();
            if (methods == null || !methods.isObject()) {
                return;
            }
            methods.fieldNames().forEachRemaining(method -> {
                if (isHttpMethod(method)) {
                    operations.add(method.toUpperCase() + " " + pathEntry.getKey());
                }
            });
        });
        Collections.sort(operations);
        return operations;
    }

    private static boolean isHttpMethod(String method) {
        return "get".equalsIgnoreCase(method)
                || "put".equalsIgnoreCase(method)
                || "post".equalsIgnoreCase(method)
                || "delete".equalsIgnoreCase(method)
                || "patch".equalsIgnoreCase(method)
                || "options".equalsIgnoreCase(method)
                || "head".equalsIgnoreCase(method)
                || "trace".equalsIgnoreCase(method);
    }

    private static JsonNode canonicalizeNode(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode canonicalArray = CANONICAL_JSON.createArrayNode();
            for (JsonNode item : node) {
                canonicalArray.add(canonicalizeNode(item));
            }
            return canonicalArray;
        }
        if (node.isObject()) {
            ObjectNode canonicalObject = CANONICAL_JSON.createObjectNode();
            List<String> fieldNames = new ArrayList<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            Collections.sort(fieldNames);
            for (String fieldName : fieldNames) {
                canonicalObject.set(fieldName, canonicalizeNode(node.get(fieldName)));
            }
            return canonicalObject;
        }
        return node;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                builder.append(Character.forDigit((b >>> 4) & 0x0f, 16));
                builder.append(Character.forDigit(b & 0x0f, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    private JsonNode fetchCurrentSpecNode() throws IOException {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(30_000);
        requestFactory.setReadTimeout(120_000);
        rest.getRestTemplate().setRequestFactory(requestFactory);

        ResponseEntity<String> json = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(json.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.getBody()).as("OpenAPI payload").isNotBlank();
        return CANONICAL_JSON.readTree(json.getBody());
    }

    private void assertOperationContract(JsonNode root,
                                         String path,
                                         String method,
                                         String expectedRequestRef,
                                         String expectedResponseCode,
                                         String expectedResponseRef) {
        JsonNode operation = root.path("paths").path(path).path(method);
        assertThat(operation.isMissingNode())
                .withFailMessage("Missing %s %s from generated OpenAPI spec", method.toUpperCase(), path)
                .isFalse();

        if (expectedRequestRef == null) {
            assertThat(operation.path("requestBody").isMissingNode())
                    .withFailMessage("Did not expect a request body for %s %s", method.toUpperCase(), path)
                    .isTrue();
        } else {
            assertThat(operation.path("requestBody").path("content").path("application/json").path("schema").path("$ref").asText())
                    .withFailMessage("Unexpected request contract for %s %s", method.toUpperCase(), path)
                    .isEqualTo(expectedRequestRef);
        }

        JsonNode responses = operation.path("responses");
        List<String> documentedResponseCodes = new ArrayList<>();
        responses.fieldNames().forEachRemaining(documentedResponseCodes::add);
        assertThat(responses.has(expectedResponseCode))
                .withFailMessage("Expected %s response for %s %s but found %s",
                        expectedResponseCode,
                        method.toUpperCase(),
                        path,
                        documentedResponseCodes)
                .isTrue();

        JsonNode response = responses.path(expectedResponseCode);
        if (expectedResponseRef == null) {
            assertThat(response.path("content").isMissingNode() || response.path("content").isEmpty())
                    .withFailMessage("Did not expect a response body for %s %s %s", expectedResponseCode, method.toUpperCase(), path)
                    .isTrue();
            return;
        }

        JsonNode content = response.path("content");
        JsonNode schema = content.path("*/*").path("schema");
        if (schema.isMissingNode()) {
            schema = content.path("application/json").path("schema");
        }
        assertThat(schema.path("$ref").asText())
                .withFailMessage("Unexpected response contract for %s %s %s", expectedResponseCode, method.toUpperCase(), path)
                .isEqualTo(expectedResponseRef);
    }
}
