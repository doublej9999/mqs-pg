import request from './request'

/** 数据源：连接配置 + 目标库结构探查（下拉框的数据来源） */

export function listDatasources() {
  return request({ url: '/datasources', method: 'get' })
}

export function getDatasource(id) {
  return request({ url: `/datasources/${id}`, method: 'get' })
}

export function createDatasource(data) {
  return request({ url: '/datasources', method: 'post', data })
}

export function updateDatasource(id, data) {
  return request({ url: `/datasources/${id}`, method: 'put', data })
}

export function deleteDatasource(id) {
  return request({ url: `/datasources/${id}`, method: 'delete' })
}

/**
 * 试连。
 * @param data 连接参数；password 留空时若带上 id，后端会回落到已保存的密码
 */
export function testDatasource(data, id) {
  return request({
    url: '/datasources/test',
    method: 'post',
    data,
    params: id ? { id } : undefined
  })
}

export function testStoredDatasource(id) {
  return request({ url: `/datasources/${id}/test`, method: 'post' })
}

// ---- 结构探查 ----

export function listSchemas(id) {
  return request({ url: `/datasources/${id}/schemas`, method: 'get' })
}

export function listTables(id, schema) {
  return request({ url: `/datasources/${id}/schemas/${schema}/tables`, method: 'get' })
}

export function listColumns(id, schema, table) {
  return request({
    url: `/datasources/${id}/schemas/${schema}/tables/${table}/columns`,
    method: 'get'
  })
}
