package com.microsoft.jenkins.artifactmanager;

import com.microsoft.jenkins.azurecommons.telemetry.AppInsightsClientFactory;
import com.microsoft.jenkins.azurecommons.telemetry.AzureHttpRecorder;
import hudson.Plugin;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.Map;

public class AzureArtifactManagerPlugin extends Plugin {
    public static void sendEvent(final String item, final String action, final Map<String, String> properties) {
        AppInsightsClientFactory.getInstance(AzureArtifactManagerPlugin.class)
                .sendEvent(item, action, properties, false);
    }

    public static class AzureTelemetryInterceptor implements Interceptor {
        @Override
        public Response intercept(final Chain chain) throws IOException {
            final Request request = chain.request();
            final Response response = chain.proceed(request);
            new AzureHttpRecorder(AppInsightsClientFactory.getInstance(AzureArtifactManagerPlugin.class))
                    .record(new AzureHttpRecorder.HttpRecordable()
                            .withHttpCode(response.code())
                            .withHttpMessage(response.message())
                            .withHttpMethod(request.method())
                            .withRequestUri(request.url().uri())
                            .withRequestId(response.header("x-ms-request-id"))
                    );
            return response;
        }
    }
}
