package com.mingzy.dbagent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 静态资源缓存策略：
 * - /assets/**（带内容哈希）：长缓存 immutable，文件名变了即自然失效；
 * - 入口 HTML：no-cache，浏览器每次协商校验（ETag/Last-Modified），
 *   保证发版后刷新页面一定能拿到新 index.html，避免旧页面引用旧 chunk。
 */
@Component
public class CacheControlFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/assets/")) {
            response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        } else if (path.equals("/") || path.endsWith(".html") || path.endsWith(".htm")) {
            response.setHeader("Cache-Control", "no-cache");
        }
        chain.doFilter(request, response);
    }
}
