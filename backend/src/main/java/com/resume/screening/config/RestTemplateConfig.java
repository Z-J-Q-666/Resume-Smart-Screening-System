package com.resume.screening.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    /**
     * 专门给 AI 服务用：错误响应不抛异常，让业务自己读 body 里的 code，
     * 这样才能把 2001/2003 和网络故障区分开。
     */
    @Bean
    public RestTemplate aiRestTemplate(AppProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getAi().getConnectTimeoutMs());
        factory.setReadTimeout(props.getAi().getReadTimeoutMs());

        RestTemplate template = new RestTemplate(factory);
        ResponseErrorHandler noThrow = new ResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(org.springframework.http.client.ClientHttpResponse response) {
                // never throw
            }
        };
        template.setErrorHandler(noThrow);
        return template;
    }
}
