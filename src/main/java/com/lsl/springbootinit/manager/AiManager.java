package com.lsl.springbootinit.manager;

import com.github.rholder.retry.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.lsl.springbootinit.common.ErrorCode;
import com.lsl.springbootinit.exception.BusinessException;
import com.yupi.yucongming.dev.client.YuCongMingClient;
import com.yupi.yucongming.dev.common.BaseResponse;
import com.yupi.yucongming.dev.model.DevChatRequest;
import com.yupi.yucongming.dev.model.DevChatResponse;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * 用于对接 AI 平台
 */
@Service
public class AiManager {

    @Resource
    private YuCongMingClient yuCongMingClient;


    // 设置重试，重试次数2次，重试间隔2s
    private final Retryer<String> retryer = RetryerBuilder.<String>newBuilder()
            .retryIfResult(result->(!isValidResult(result)))
            .withStopStrategy(StopStrategies.stopAfterAttempt(2))
            .withWaitStrategy(WaitStrategies.fixedWait(2, TimeUnit.SECONDS))
            .build();


    /**
     * AI 对话
     *
     * @param modelId
     * @param message
     * @return
     */

    // 1779142609428979713
    public String doChat(long modelId, String message) {
        DevChatRequest devChatRequest = new DevChatRequest();
        devChatRequest.setModelId(modelId);
        devChatRequest.setMessage(message);
        BaseResponse<DevChatResponse> response = yuCongMingClient.doChat(devChatRequest);
        if (response == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 响应错误");
        }
        return response.getData().getContent();
    }


    // AI 对话错误重试
    public String doChatWithRetry(long modelId,String userPrompt) throws Exception {
        try {
            Callable<String> callable = () -> {
                // 在这里调用你的doChat方法
                return doChat(modelId,userPrompt);
            };
            return retryer.call(callable);
        } catch (RetryException e) {
            throw new Exception("重试失败", e);
        }
    }


    /**
     * 分析结果是否存在错误
     * @param result
     * @return
     */
    private boolean isValidResult(String result) {
        String[] splits = result.split("【【【【【");
        if (splits.length < 3)
            return false;
        String genChart = splits[1].trim();
        try {
            JsonObject chartJson = JsonParser.parseString(genChart).getAsJsonObject();
            // 检查是否存在 "title" 字段
            if (!chartJson.has("title")) {
                return false;
            }
            // 检查 "title" 字段的内容是否为空或不含 "text" 字段
            JsonElement titleElement = chartJson.getAsJsonObject("title").get("text");
            if (titleElement == null || titleElement.isJsonNull()) {
                return false;
            }
            String titleText = titleElement.getAsString();
            if (titleText.isEmpty()) {
                return false;
            }
        } catch (JsonSyntaxException e) {
            // Json解析异常，直接返回 false
            return false;
        }
        return true;
    }

}
