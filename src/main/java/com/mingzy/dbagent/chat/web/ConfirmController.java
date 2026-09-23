package com.mingzy.dbagent.chat.web;

import com.mingzy.dbagent.chat.ConfirmationService;
import com.mingzy.dbagent.common.BrowserFingerprint;
import com.mingzy.dbagent.common.Result;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/confirm")
public class ConfirmController {

    private final ConfirmationService service;
    public ConfirmController(ConfirmationService service) { this.service = service; }

    @PostMapping("/{id}/approve")
    public Result<Void> approve(@PathVariable long id,
            @RequestHeader(value = BrowserFingerprint.HEADER, required = false) String clientFingerprint) {
        service.approve(id, BrowserFingerprint.require(clientFingerprint));
        return Result.ok(null);
    }

    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable long id,
            @RequestHeader(value = BrowserFingerprint.HEADER, required = false) String clientFingerprint) {
        service.reject(id, BrowserFingerprint.require(clientFingerprint));
        return Result.ok(null);
    }
}
