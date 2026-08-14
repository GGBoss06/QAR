package com.qar.securitysystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qar.securitysystem.model.PersonRecordEntity;
import com.qar.securitysystem.repo.PersonRecordRepository;
import com.qar.securitysystem.util.AesGcmUtil;
import com.qar.securitysystem.util.IdUtil;
import com.qar.securitysystem.util.RSAUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        properties = {
                "app.security.cookieName=QAR_SESSION",
                "app.security.cookieSecure=false",
                "app.security.cookieSameSite=Strict",
                "app.security.sessionTtlMinutes=60",
                "app.admin.username=admin",
                "app.admin.password=CAUCqar",
                "app.person.seedEnabled=false",
                "spring.datasource.url=${TEST_DB_URL:jdbc:mysql://127.0.0.1:3306/qar_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}",
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.datasource.username=${TEST_DB_USERNAME:root}",
                "spring.datasource.password=${TEST_DB_PASSWORD:}"
        }
)
public class AuthAndFileFlowTests {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private PersonRecordRepository personRecordRepository;

    private MockMvc mvc;

    @Test
    void loginWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"emailOrUsername\":\"admin\",\"password\":\"test\"}"))
                .andExpect(status().isForbidden());
    }

    @BeforeEach
    void setup() {
        this.mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    void transport_encrypted_upload_works() throws Exception {
        PersonRecordEntity pr = new PersonRecordEntity();
        pr.setId(IdUtil.newId());
        pr.setPersonNo("20260001");
        pr.setFullName("张三");
        pr.setIdLast4("1234");
        pr.setPhone("13800000000");
        pr.setDepartment("飞行一部");
        pr.setAirline("CAUC");
        pr.setPositionTitle("机长");
        pr.setCreatedAt(Instant.now());
        if (!personRecordRepository.existsByPersonNo("20260001")) {
            personRecordRepository.save(pr);
        }

        String adminSetCookie = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"emailOrUsername\":\"admin\",\"password\":\"CAUCqar\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.SET_COOKIE);
        String adminToken = adminSetCookie.split("QAR_SESSION=")[1].split(";", 2)[0];
        Cookie adminCookie = new Cookie("QAR_SESSION", adminToken);

        String handshakeJson = mvc.perform(post("/api/transport/handshake")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"protocol\":\"tls\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String serverPublicKeyBase64 = OBJECT_MAPPER.readTree(handshakeJson).get("serverPublicKey").asText();
        PublicKey serverPublicKey = RSAUtil.getPublicKey(serverPublicKeyBase64);

        javax.crypto.SecretKey aesKey = AesGcmUtil.generateKey();
        byte[] transportIv = AesGcmUtil.newIv();
        String requestBody = """
                {"encryptedData":"aGVsbG8tdHJhbnNwb3J0LXVwbG9hZA==","wrappedKey":"","originalName":"transport.txt","contentType":"text/plain","sizeBytes":22,"policy":"role:user personNo:20260001","personNo":"20260001"}
                """.trim();
        byte[] transportCiphertext = AesGcmUtil.encrypt(
                aesKey,
                transportIv,
                requestBody.getBytes(StandardCharsets.UTF_8),
                "POST /api/files/encrypted".getBytes(StandardCharsets.UTF_8)
        );
        String transportEnvelope = OBJECT_MAPPER.writeValueAsString(Map.of(
                "iv", Base64.getEncoder().encodeToString(transportIv),
                "ciphertext", Base64.getEncoder().encodeToString(transportCiphertext)
        ));
        String wrappedTransportKey = Base64.getEncoder().encodeToString(RSAUtil.encrypt(aesKey.getEncoded(), serverPublicKey));

        String encryptedResponse = mvc.perform(post("/api/files/encrypted")
                        .cookie(adminCookie)
                        .with(csrf())
                        .header("X-QAR-Encrypted", "1")
                        .header("X-QAR-Transport", "tls")
                        .header("X-QAR-Wrapped-Key", wrappedTransportKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transportEnvelope))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String responseIvBase64 = OBJECT_MAPPER.readTree(encryptedResponse).get("iv").asText();
        String responseCiphertextBase64 = OBJECT_MAPPER.readTree(encryptedResponse).get("ciphertext").asText();
        byte[] responsePlain = AesGcmUtil.decrypt(
                aesKey,
                Base64.getDecoder().decode(responseIvBase64),
                Base64.getDecoder().decode(responseCiphertextBase64),
                "POST /api/files/encrypted".getBytes(StandardCharsets.UTF_8)
        );
        String responseJson = new String(responsePlain, StandardCharsets.UTF_8);

        assertThat(responseJson).contains("\"id\"");
        assertThat(responseJson).contains("\"transport.txt\"");
    }

    @Test
    void register_login_upload_download_admin_export() throws Exception {
        PersonRecordEntity pr = new PersonRecordEntity();
        pr.setId(IdUtil.newId());
        pr.setPersonNo("user1");
        pr.setFullName("User One");
        pr.setIdLast4("1234");
        pr.setPhone("13800000000");
        pr.setDepartment("飞行一部");
        pr.setAirline("CAUC");
        pr.setPositionTitle("机长");
        pr.setCreatedAt(Instant.now());
        personRecordRepository.save(pr);

        mvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"emailOrUsername\":\"admin\",\"fullName\":\"x\",\"idLast4\":\"0000\",\"password\":\"x\",\"passwordConfirm\":\"x\"}"))
                .andExpect(status().isBadRequest());

        String registrationJson = mvc.perform(post("/api/auth/register")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"emailOrUsername\":\"user1\",\"fullName\":\"User One\",\"idLast4\":\"1234\",\"contact\":\"13800000000\",\"airline\":\"CAUC\",\"positionTitle\":\"机长\",\"department\":\"飞行一部\",\"password\":\"Passw0rd!\",\"passwordConfirm\":\"Passw0rd!\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(registrationJson).doesNotContain("privateKey");

        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"emailOrUsername\":\"user1\",\"password\":\"Passw0rd!\"}"))
                .andExpect(status().isForbidden());

        String adminSetCookie = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"emailOrUsername\":\"admin\",\"password\":\"CAUCqar\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.SET_COOKIE);

        assertThat(adminSetCookie).contains("QAR_SESSION=");
        String adminToken = adminSetCookie.split("QAR_SESSION=")[1].split(";", 2)[0];
        Cookie adminCookie = new Cookie("QAR_SESSION", adminToken);

        String reqList = mvc.perform(get("/api/admin/account-requests").cookie(adminCookie))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(reqList).contains("\"personNo\":\"user1\"");
        String reqId = reqList.split("\"id\":\"")[1].split("\"", 2)[0];

        mvc.perform(post("/api/admin/account-requests/" + reqId + "/approve")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"adminNote\":\"ok\"}"))
                .andExpect(status().isOk());

        String setCookie = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"emailOrUsername\":\"user1\",\"password\":\"Passw0rd!\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.SET_COOKIE);

        assertThat(setCookie).contains("QAR_SESSION=");
        String token = setCookie.split("QAR_SESSION=")[1].split(";", 2)[0];
        Cookie sessionCookie = new Cookie("QAR_SESSION", token);

        byte[] payload = "hello-qar".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", payload);

        mvc.perform(multipart("/api/files")
                        .file(file)
                        .param("policy", "role:user")
                        .cookie(sessionCookie)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        String fileJson = mvc.perform(multipart("/api/files")
                        .file(file)
                        .param("policy", "role:user")
                        .param("personNo", "user1")
                        .cookie(adminCookie)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(fileJson).contains("\"id\"");
        String fileId = fileJson.split("\"id\":\"")[1].split("\"", 2)[0];
        assertThat(fileJson).contains("\"protectionStatus\":\"ATTRIBUTE_CONTROLLED\"");
        assertThat(fileJson).doesNotContain("wrappedKey", "LABE_LATTICE_BC:");

        byte[] downloaded = mvc.perform(get("/api/files/" + fileId + "/download")
                        .cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(downloaded).isEqualTo(payload);

        String payloadJson = mvc.perform(get("/api/files/" + fileId + "/payload")
                        .cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(payloadJson).contains(Base64.getEncoder().encodeToString(payload));

        mvc.perform(post("/api/feedback")
                        .cookie(sessionCookie)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"type\":\"bug\",\"subject\":\"下载校验\",\"message\":\"下载正常，但我想确认一下流程\",\"relatedFileId\":\"" + fileId + "\"}"))
                .andExpect(status().isOk());

        String feedbackList = mvc.perform(get("/api/admin/feedback").cookie(adminCookie))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(feedbackList).contains("下载校验");
        String feedbackId = feedbackList.split("\"id\":\"")[1].split("\"", 2)[0];

        mvc.perform(patch("/api/admin/feedback/" + feedbackId)
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"status\":\"RESOLVED\",\"adminReply\":\"收到，流程正常\"}"))
                .andExpect(status().isOk());

        byte[] zipBytes = mvc.perform(get("/api/admin/files/export").cookie(adminCookie))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        boolean found = false;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith("_a.txt")) {
                    byte[] buf = zis.readAllBytes();
                    assertThat(buf).isEqualTo(payload);
                    found = true;
                    break;
                }
            }
        }
        assertThat(found).isTrue();
    }
}
