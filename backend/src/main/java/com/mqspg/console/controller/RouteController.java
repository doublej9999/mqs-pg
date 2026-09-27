package com.mqspg.console.controller;

import com.mqspg.common.api.ApiResponse;
import com.mqspg.config.dto.DraftDto;
import com.mqspg.config.dto.DraftRequest;
import com.mqspg.config.dto.RouteDetailDto;
import com.mqspg.config.dto.RouteRequest;
import com.mqspg.config.dto.RouteSummaryDto;
import com.mqspg.config.dto.ValidationResultDto;
import com.mqspg.config.dto.VersionSummaryDto;
import com.mqspg.config.service.ConfigAdminService;
import com.mqspg.config.service.ConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 路由与配置版本 API（tech-design §13.1）。 */
@RestController
@RequestMapping("/api/routes")
@RequiredArgsConstructor
public class RouteController {

    private final ConfigService configService;
    private final ConfigAdminService admin;

    // ---- 查询 ----

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

    // ---- 路由增删改 ----

    /** 新建路由。初始为 INACTIVE，需再配一个版本并激活才会开始消费。 */
    @PostMapping
    public ApiResponse<RouteSummaryDto> create(@RequestBody RouteRequest req) {
        return ApiResponse.ok(admin.createRoute(req));
    }

    /** 修改路由。Topic/Tag 变化会重建消费者。 */
    @PutMapping("/{routeId}")
    public ApiResponse<RouteSummaryDto> update(@PathVariable long routeId,
                                               @RequestBody RouteRequest req) {
        return ApiResponse.ok(admin.updateRoute(routeId, req));
    }

    /**
     * 删除路由。
     *
     * @param force 路由还有未完成的重试任务时，默认拒绝删除；确认丢弃才传 true
     */
    @DeleteMapping("/{routeId}")
    public ApiResponse<Void> delete(@PathVariable long routeId,
                                    @RequestParam(name = "force", defaultValue = "false") boolean force) {
        admin.deleteRoute(routeId, force);
        return ApiResponse.ok();
    }

    // ---- 草稿 ----

    /** 读取草稿：没有草稿时基于生效版本合成一份未持久化的编辑视图。 */
    @GetMapping("/{routeId}/draft")
    public ApiResponse<DraftDto> getDraft(@PathVariable long routeId) {
        return ApiResponse.ok(admin.getDraft(routeId));
    }

    /** 保存草稿。 */
    @PutMapping("/{routeId}/draft")
    public ApiResponse<DraftDto> saveDraft(@PathVariable long routeId,
                                           @RequestBody DraftRequest req) {
        return ApiResponse.ok(admin.saveDraft(routeId, req));
    }

    /** 按目标表列自动生成映射草稿（PRD §36）。 */
    @PostMapping("/{routeId}/draft/auto-generate")
    public ApiResponse<DraftDto> autoGenerate(@PathVariable long routeId) {
        return ApiResponse.ok(admin.autoGenerateMappings(routeId));
    }

    // ---- 版本生命周期 ----

    /** 以当前生效版本为模板新建草稿版本。 */
    @PostMapping("/{routeId}/versions")
    public ApiResponse<VersionSummaryDto> createVersion(@PathVariable long routeId) {
        return ApiResponse.ok(admin.createVersion(routeId));
    }

    /** 只做校验，不改状态。 */
    @GetMapping("/{routeId}/versions/{version}/validate")
    public ApiResponse<ValidationResultDto> validate(@PathVariable long routeId,
                                                     @PathVariable int version) {
        return ApiResponse.ok(admin.validateVersion(routeId, version));
    }

    /** 发布。校验不通过时返回 valid=false 与问题清单，而不是抛异常。 */
    @PostMapping("/{routeId}/versions/{version}/publish")
    public ApiResponse<ValidationResultDto> publish(@PathVariable long routeId,
                                                    @PathVariable int version) {
        return ApiResponse.ok(admin.publishVersion(routeId, version));
    }

    /** 删除版本。ACTIVE 版本与当前生效版本不可删。 */
    @DeleteMapping("/{routeId}/versions/{version}")
    public ApiResponse<Void> deleteVersion(@PathVariable long routeId, @PathVariable int version) {
        admin.deleteVersion(routeId, version);
        return ApiResponse.ok();
    }

    /** 激活指定版本。刷新注册表与启动消费者由 AFTER_COMMIT 监听器完成。 */
    @PostMapping("/{routeId}/versions/{version}/activate")
    public ApiResponse<Void> activate(@PathVariable long routeId, @PathVariable int version) {
        configService.activate(routeId, version);
        return ApiResponse.ok();
    }

    /** 回滚到当前生效版本之前最近的可激活版本。 */
    @PostMapping("/{routeId}/rollback")
    public ApiResponse<Map<String, Object>> rollback(@PathVariable long routeId) {
        int version = admin.rollback(routeId);
        return ApiResponse.ok(Map.of("activatedVersion", version));
    }
}
