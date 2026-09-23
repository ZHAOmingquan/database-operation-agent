package com.mingzy.dbagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 前端 History 路由回退：直接访问/刷新 /chat 等前端路由时转发到 index.html。
     * 穷举已知 SPA 路径（前端新增路由时同步维护），避免与 /api /ws /mcp 冲突。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String path : new String[]{"/chat", "/datasources", "/models", "/dict", "/configs"}) {
            registry.addViewController(path).setViewName("forward:/index.html");
        }
    }
}
