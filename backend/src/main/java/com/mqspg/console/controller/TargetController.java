package com.mqspg.console.controller;

import com.mqspg.common.api.ApiResponse;
import com.mqspg.config.dto.TargetDto;
import com.mqspg.config.dto.TargetRequest;
import com.mqspg.config.service.ConfigAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 目标表定义 API（「写到哪个库的哪个 schema 的哪张表」）。 */
@RestController
@RequestMapping("/api/targets")
@RequiredArgsConstructor
public class TargetController {

    private final ConfigAdminService admin;

    @GetMapping
    public ApiResponse<List<TargetDto>> list() {
        return ApiResponse.ok(admin.listTargets());
    }

    @PostMapping
    public ApiResponse<TargetDto> create(@RequestBody TargetRequest req) {
        return ApiResponse.ok(admin.createTarget(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<TargetDto> update(@PathVariable long id, @RequestBody TargetRequest req) {
        return ApiResponse.ok(admin.updateTarget(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        admin.deleteTarget(id);
        return ApiResponse.ok();
    }
}
