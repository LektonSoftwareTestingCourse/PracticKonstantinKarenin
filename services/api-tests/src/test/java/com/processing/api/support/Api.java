package com.processing.api.support;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** One place for service URLs, headers, HTTP timeouts and request evidence. */
public final class Api {
    private Api() {}

    public static RequestSpecification gateway() {
        return service("gateway", 8080);
    }

    public static RequestSpecification cards() {
        return service("cards", 8081);
    }

    public static RequestSpecification authorization() {
        return service("authorization", 8083);
    }

    public static RequestSpecification logger() {
        return service("logger", 8088);
    }

    public static RequestSpecification service(String name, int port) {
        var config = RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
                .setParam("http.connection.timeout", 5000).setParam("http.socket.timeout", 45000));
        var spec = new RequestSpecBuilder()
                .setBaseUri(System.getProperty("api." + name + ".url", "http://localhost:" + port))
                .setContentType(ContentType.JSON).setAccept(ContentType.JSON)
                .setConfig(config).addFilter((request, response, context) -> {
                    var result = context.next(request, response);
                    record(request.getMethod() + " " + request.getURI() + " " + result.statusCode());
                    return result;
                }).build();
        return RestAssured.given().spec(spec).log().ifValidationFails(LogDetail.ALL);
    }

    private static synchronized void record(String line) {
        try {
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/api-requests.log"), line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write API request evidence", exception);
        }
    }
}
