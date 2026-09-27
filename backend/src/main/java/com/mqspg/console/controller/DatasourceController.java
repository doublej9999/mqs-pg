package com.mqspg.console.controller;

import com.mqspg.common.api.ApiResponse;
import com.mqspg.config.dto.ColumnDto;
import com.mqspg.config.dto.ConnectionTestDto;
import com.mqspg.config.dto.DatasourceDto;
import com.mqspg.config.dto.DatasourceRequest;
import com.mqspg.config.service.ConfigAdminService;
import com.mqspg.config.service.IntrospectionService;
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

/**
 * 数据源与目标库结构探查 API。
 *
 * <p>结构探查（schemas / tables / columns）放在这里而不是单独的「元数据」控制器，
 * 是因为它们全都以「某个数据源」为前提，路径上带上 {@code {id}} 更自然，
 * 页面也不必先问一次「用哪个数据源」。
 */
@RestController
@RequestMapping("/api/datasources")
@RequiredArgsConstructor
public class DatasourceController {

    private final ConfigAdminService admin;
    private final IntrospectionService introspection;

    @GetMapping
    public ApiResponse<List<DatasourceDto>> list() {
        return ApiResponse.ok(admin.listDatasources());
    }

    @GetMapping("/{id}")
    public ApiResponse<DatasourceDto> get(@PathVariable long id) {
        return ApiResponse.ok(admin.getDatasource(id));
    }

    @PostMapping
    public ApiResponse<DatasourceDto> create(@RequestBody DatasourceRequest req) {
        return ApiResponse.ok(admin.createDatasource(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<DatasourceDto> update(@PathVariable long id, @RequestBody DatasourceRequest req) {
        return ApiResponse.ok(admin.updateDatasource(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        admin.deleteDatasource(id);
        return ApiResponse.ok();
    }

    /**
     * 用请求体里的参数试连，**保存之前**就能发现连不上。
     *
     * @param id 可选。带上已保存数据源的 id 时，请求体里留空的密码会回落到已保存的密码 ——
     *           编辑已有数据源时不必重新输一遍密码
     */
    @PostMapping("/test")
    public ApiResponse<ConnectionTestDto> test(@RequestParam(required = false) Long id,
                                               @RequestBody DatasourceRequest req) {
        return ApiResponse.ok(introspection.testCandidate(id, req));
    }

    /** 用已保存的配置试连。 */
    @PostMapping("/{id}/test")
    public ApiResponse<ConnectionTestDto> testStored(@PathVariable long id) {
        return ApiResponse.ok(introspection.testStored(id));
    }

    // ---- 结构探查：给页面的下拉框供数 ----

    @GetMapping("/{id}/schemas")
    public ApiResponse<List<String>> schemas(@PathVariable long id) {
        return ApiResponse.ok(introspection.listSchemas(id));
    }

    @GetMapping("/{id}/schemas/{schema}/tables")
    public ApiResponse<List<String>> tables(@PathVariable long id, @PathVariable String schema) {
        return ApiResponse.ok(introspection.listTables(id, schema));
    }

    @GetMapping("/{id}/schemas/{schema}/tables/{table}/columns")
    public ApiResponse<List<ColumnDto>> columns(@PathVariable long id,
                                                @PathVariable String schema,
                                                @PathVariable String table) {
        return ApiResponse.ok(introspection.listColumns(id, schema, table));
    }
}
