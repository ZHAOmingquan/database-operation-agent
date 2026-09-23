package com.mingzy.dbagent.chat.web;

import com.mingzy.dbagent.chat.ConfirmationService;
import com.mingzy.dbagent.common.Result;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/confirm")
public class ConfirmController {

    private final ConfirmationService service;
    public ConfirmController(ConfirmationService service) { this.service = service; }

    @PostMapping("/{id}/approve")
    public Result<Void> approve(@PathVariable long id) { service.approve(id); return Result.ok(null); }

    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable long id) { service.reject(id); return Result.ok(null); }
}
