package com.nmslite.services;

import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Test service to check ProxyGen compatibility
 */
@ProxyGen
public interface TestService {

    static TestService create(Vertx vertx) {
        return new TestServiceImpl(vertx);
    }

    static TestService createProxy(Vertx vertx, String address) {
        return new TestServiceVertxEBProxy(vertx, address);
    }

    // Test with Future return type
    Future<JsonObject> testMethod(String param);

    // Test with void return type
    void testVoidMethod(String param);
}

class TestServiceImpl implements TestService {
    private final Vertx vertx;

    public TestServiceImpl(Vertx vertx) {
        this.vertx = vertx;
    }

    @Override
    public Future<JsonObject> testMethod(String param) {
        return Future.succeededFuture(new JsonObject().put("result", param));
    }

    @Override
    public void testVoidMethod(String param) {
        // Do nothing
    }
}
