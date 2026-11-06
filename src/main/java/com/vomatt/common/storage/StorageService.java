package com.vomatt.common.storage;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.net.URI;
import java.util.Optional;

/**
 * Object storage in one S3-compatible bucket (Railway Bucket in production, MinIO locally).
 * Unconfigured (no endpoint or bucket) the app still starts; every call then fails with 503
 * {@code common.storage_unavailable}.
 */
@Slf4j
@Service
public class StorageService {

    private final S3Client client;
    private final String bucket;

    public StorageService(@Value("${app.storage.endpoint:}") String endpoint,
                          @Value("${app.storage.region:auto}") String region,
                          @Value("${app.storage.bucket:}") String bucket,
                          @Value("${app.storage.access-key-id:}") String accessKeyId,
                          @Value("${app.storage.secret-access-key:}") String secretAccessKey,
                          @Value("${app.storage.path-style:false}") boolean pathStyle) {
        this.bucket = bucket;
        if (endpoint.isBlank() || bucket.isBlank()) {
            log.warn("Object storage is not configured (STORAGE_ENDPOINT / STORAGE_BUCKET); avatar uploads are disabled");
            this.client = null;
            return;
        }
        this.client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .forcePathStyle(pathStyle)
                // S3-compatible stores do not all accept the SDK's default CRC32 checksums; send them only when required
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    public void put(String key, byte[] content, String contentType) {
        client().putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(content));
    }

    /** The object's stream, which the caller must close, or empty when no object has this key. */
    public Optional<ResponseInputStream<GetObjectResponse>> get(String key) {
        try {
            return Optional.of(client().getObject(b -> b.bucket(bucket).key(key)));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    /** Deletes the object; a missing key is not an error. */
    public void delete(String key) {
        client().deleteObject(b -> b.bucket(bucket).key(key));
    }

    private S3Client client() {
        if (client == null) {
            throw ApiException.serviceUnavailable(MessageKey.COMMON_STORAGE_UNAVAILABLE);
        }
        return client;
    }

    @PreDestroy
    void close() {
        if (client != null) {
            client.close();
        }
    }
}
