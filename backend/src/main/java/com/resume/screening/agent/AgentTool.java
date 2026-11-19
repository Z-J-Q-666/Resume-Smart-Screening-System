package com.resume.screening.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 一个可以被模型调用的工具。
 *
 * <p>约定：execute 返回**紧凑 JSON 字符串**，直接进模型的上下文。
 * 所以返回体里不要塞 parsed_json 这种大字段——每多一个 token 都是钱，也会挤掉真正有用的信息。
 */
public interface AgentTool {

    /** 蛇形命名，必须与 parameters 里的 tool name 一致 */
    String name();

    /** 给模型看的说明。写清楚"什么时候该用我"，比写"我能干什么"更有效 */
    String description();

    /** JSON Schema，原样透传给模型 */
    Map<String, Object> parameters();

    String execute(JsonNode args);
}
