package com.mqspg.console.controller;

import com.mqspg.common.api.ApiResponse;
import com.mqspg.config.dto.RouteDetailDto;
import com.mqspg.config.dto.RouteSummaryDto;
import com.mqspg.config.dto.VersionSummaryDto;
import com.mqspg.config.service.ConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 路由与配置版本 API（tech-design §13.1）。 */
@RestController
@RequestMapping("/api/routes")
@RequiredArgsConstructor
public class RouteController {

    private final ConfigService configService;

    /** 路由列表。 */
    @GetMapping
    public ApiResponse<List<RouteSummaryDto>> list() {
        return ApiResponse.ok(configService.listRoutes());
    }

    /** 路由详情：含目标表、当前 ACTIVE 快照与版本列表。 */
    @GetMapping("/{routeId}")
    public ApiResponse<RouteDetailDto> detail(@PathVariable long routeId) {
        return ApiResponse.ok(configService.getRoute(routeId));
    }

    /** 版本列表。 */
    @GetMapping("/{routeId}/versions")
    public ApiResponse<List<VersionSummaryDto>> versions(@PathVariable long routeId) {
        return ApiResponse.ok(configService.listVersions(routeId));
    }

    /** 激活指定版本。刷新注册表由 AFTER_COMMIT 监听器完成。 */
    @PostMapping("/{routeId}/versions/{version}/activate")
    public ApiResponse<Void> activate(@PathVariable long routeId, @PathVariable int version) {
        configService.activate(routeId, version);
        return ApiResponse.ok();
    }
}
