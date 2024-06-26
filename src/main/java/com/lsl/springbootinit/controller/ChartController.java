package com.lsl.springbootinit.controller;

import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.gson.*;
import com.lsl.springbootinit.annotation.AuthCheck;
import com.lsl.springbootinit.common.BaseResponse;
import com.lsl.springbootinit.common.DeleteRequest;
import com.lsl.springbootinit.common.ErrorCode;
import com.lsl.springbootinit.common.ResultUtils;
import com.lsl.springbootinit.constant.CommonConstant;
import com.lsl.springbootinit.constant.UserConstant;
import com.lsl.springbootinit.exception.BusinessException;
import com.lsl.springbootinit.exception.ThrowUtils;
import com.lsl.springbootinit.manager.AiManager;
import com.lsl.springbootinit.manager.RedisCacheManager;
import com.lsl.springbootinit.manager.RedisLimiterManager;
import com.lsl.springbootinit.manager.TaskManager;
import com.lsl.springbootinit.model.dto.chart.*;
import com.lsl.springbootinit.model.entity.Chart;
import com.lsl.springbootinit.model.entity.User;
import com.lsl.springbootinit.model.vo.BiResponse;
import com.lsl.springbootinit.mq.MessageProducer;
import com.lsl.springbootinit.service.ChartService;
import com.lsl.springbootinit.service.ScoreService;
import com.lsl.springbootinit.service.UserService;
import com.lsl.springbootinit.utils.Csv2String;
import com.lsl.springbootinit.utils.ExcelUtils;
import com.lsl.springbootinit.utils.SqlUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

import static com.lsl.springbootinit.service.impl.OpenaiServiceImpl.SYNCHRO_MAX_TOKEN;


/**
 * 图表接口
 *
 */
@RestController
@RequestMapping("/chart")
@Slf4j
public class ChartController {

    @Resource
    private ChartService chartService;

    @Resource
    private UserService userService;

    @Resource
    private ScoreService scoreService;

    @Resource
    private RedisCacheManager redisCacheManager;

    @Resource
    private RedisLimiterManager redisLimiterManager;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Resource
    private MessageProducer messageProducer;

    @Resource
    private TaskManager taskManager;

    @Resource
    private AiManager aiManager;

    /**
     * 文件AI分析
     *
     * @param multipartFile
     * @param genChartByAiRequest
     * @param request
     * @return
     */
    @PostMapping("/gen")
    public BaseResponse<BiResponse> genChartByAi(@RequestPart("file") MultipartFile multipartFile,
                                                 GenChartByAiRequest genChartByAiRequest, HttpServletRequest request) {
        String name = genChartByAiRequest.getName();
        String goal = genChartByAiRequest.getGoal();
        String chartType = genChartByAiRequest.getChartType();
        // 校验
        ThrowUtils.throwIf(StringUtils.isNotBlank(name) && name.length() > 100, ErrorCode.PARAMS_ERROR, "名称过长");
        User loginUser = userService.getLoginUser(request);
        // 校验文件大小及后缀
        long size = multipartFile.getSize();
        final long TEN_MB = 10 * 1024 * 1024L;
        ThrowUtils.throwIf(size > TEN_MB, ErrorCode.PARAMS_ERROR, "文件大小大于10M");

        String fileName = multipartFile.getOriginalFilename();
        String suffix = FileUtil.getSuffix(fileName);
        final List<String> validFileSuffix = Arrays.asList("xlsx", "csv", "xls");
        ThrowUtils.throwIf(!validFileSuffix.contains(suffix), ErrorCode.PARAMS_ERROR, "文件后缀非法");
        // 每个用户限流
        redisLimiterManager.doRateLimit("genChartByAi" + loginUser.getId());

        long biModelId = CommonConstant.BI_MODEL_ID;
        // 构造用户输入
        StringBuilder userInput = new StringBuilder();
        userInput.append("分析需求：\n");
        // 拼接分析目标
        String userGoal = "请帮我合理的分析一下数据";
        if (StringUtils.isNotBlank(goal))
            userGoal = goal;
        // todo 测试分析输入加入图表类型
        // 分析输入加入图表类型
        if (StringUtils.isNotBlank(chartType))
            userGoal += ",请使用" + chartType;
        userInput.append(userGoal).append("\n");
        userInput.append("原始数据：\n");
        // 压缩数据
        String userData = "";
        if (Objects.equals(suffix, "csv"))
            userData = Csv2String.MultipartFileToString(multipartFile);
        else{
            userData = ExcelUtils.excel2Csv(multipartFile);
            userInput.append(userData).append("\n");
        }
        BiResponse biResponse = new BiResponse();

        Chart chart = new Chart();
        // 数据规模校验 gpt3.5分析时长超过30s
        if (userData.length() > SYNCHRO_MAX_TOKEN){
            biResponse.setGenChart("");
            biResponse.setGenResult("large_size");
            biResponse.setChartId(chart.getId());
            return ResultUtils.success(biResponse);
        }
        userInput.append(userData).append("\n");
        String result = "";
        try {
            // 执行重试逻辑
            result = aiManager.doChatWithRetry(biModelId,userInput.toString());
        } catch (Exception e) {
            // 如果重试过程中出现异常，返回错误信息
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, e + "，AI生成错误");
        }
        scoreService.deductPoints(loginUser.getId(),1L);
        String[] splits = result.split("【【【【【");

        String genChart = splits[1].trim();
        String genResult = splits[2].trim();
        // Echarts代码过滤 "var option ="
        if (genChart.startsWith("var option =")) {
            // 去除 "var option ="
            genChart = genChart.replaceFirst("var\\s+option\\s*=\\s*", "");
        }

        JsonObject chartJson = JsonParser.parseString(genChart).getAsJsonObject();

        // 自动加入图表名称结尾并设置图表名称
        if (StringUtils.isEmpty(name)){
            String genChartName = String.valueOf(chartJson.getAsJsonObject("title").get("text"));
            genChartName = genChartName.replace("\"","");
            if ( !genChartName.endsWith("图") && !genChartName.endsWith("表") && !genChartName.endsWith("图表"))
                genChartName = genChartName + "图";
            chart.setName(genChartName);
        } else
            chart.setName(name);
        // 自动添加图表类型
        if (StringUtils.isEmpty(chartType)){
            JsonArray seriesArray = chartJson.getAsJsonArray("series");
            if (seriesArray.size() > 0) {
                JsonObject firstSeries = seriesArray.get(0).getAsJsonObject();
                String typeChart = firstSeries.getAsJsonObject().get("type").getAsString();
                String CnChartType = chartService.getChartTypeToCN(typeChart);
                chart.setChartType(CnChartType);
            }
        }else
            chart.setChartType(chartType);
        // 加入下载按钮
        JsonObject toolbox = new JsonObject();
        toolbox.addProperty("show", true);
        JsonObject saveAsImage = new JsonObject();
        saveAsImage.addProperty("show", true);
        saveAsImage.addProperty("excludeComponents", "['toolbox']");
        saveAsImage.addProperty("pixelRatio", 2);
        JsonObject feature = new JsonObject();
        feature.add("saveAsImage", saveAsImage);
        toolbox.add("feature", feature);
        chartJson.add("toolbox", toolbox);
        chartJson.remove("title");
        String updatedGenChart = chartJson.toString();
        chart.setGoal(userGoal);
        chart.setChartData(userData);
        chart.setGenChart(updatedGenChart);
        chart.setGenResult(genResult);
        chart.setUserId(loginUser.getId());
        chart.setStatus("succeed");
        boolean saveResult = chartService.save(chart);
        if (!saveResult)
            handleChartUpdateError(chart.getId(),"图表信息保存失败");
        biResponse.setGenChart(updatedGenChart);
        biResponse.setGenResult(genResult);
        biResponse.setChartId(chart.getId());
        return ResultUtils.success(biResponse);
    }

    /**
     * 图表错误状态处理
     * @param chartId
     * @param execMessage
     */
    private void handleChartUpdateError(long chartId, String execMessage){
        Chart updateChart = new Chart();
        updateChart.setId(chartId);
        updateChart.setStatus("failed");
        updateChart.setExecMessage(execMessage);
        boolean b = chartService.updateById(updateChart);
        if (!b)
            log.error("更新图表失败状态错误" + chartId + ":" + execMessage);
    }

    /**
     * 文件AI分析
     *
     * @param multipartFile
     * @param genChartByAiRequest
     * @param request
     * @return
     */
    @PostMapping("/gen/async")
    public BaseResponse<BiResponse> genChartByAiAsync(@RequestPart("file") MultipartFile multipartFile,
                                                      GenChartByAiRequest genChartByAiRequest, HttpServletRequest request) {
        String name = genChartByAiRequest.getName();
        String goal = genChartByAiRequest.getGoal();
        String chartType = genChartByAiRequest.getChartType();
        // 校验
        ThrowUtils.throwIf(StringUtils.isNotBlank(name) && name.length() > 100, ErrorCode.PARAMS_ERROR, "名称过长");
        User loginUser = userService.getLoginUser(request);
        // 校验文件大小及后缀
        long size = multipartFile.getSize();
        final long TEN_MB = 10 * 1024 * 1024L;
        ThrowUtils.throwIf(size > TEN_MB, ErrorCode.PARAMS_ERROR, "文件大小大于10M");
        String fileName = multipartFile.getOriginalFilename();
        String suffix = FileUtil.getSuffix(fileName);
        final List<String> validFileSuffix = Arrays.asList("xlsx", "csv", "xls");
        ThrowUtils.throwIf(!validFileSuffix.contains(suffix), ErrorCode.PARAMS_ERROR, "文件后缀非法");
        // 每个用户限流
        redisLimiterManager.doRateLimit("genChartByAi" + loginUser.getId());
        // 构造用户输入
        StringBuilder userInput = new StringBuilder();
        userInput.append("分析需求：\n");
        // 拼接分析目标
        String userGoal = "请帮我合理的分析一下数据";
        if (StringUtils.isNotBlank(goal))
            userGoal = goal;
        // 分析输入加入图表类型
        if (StringUtils.isNotBlank(chartType))
            userGoal += ",请使用" + chartType;
        userInput.append(userGoal).append("\n");
        userInput.append("原始数据：\n");
        // 压缩数据
        String userData = ExcelUtils.excel2Csv(multipartFile);
        userInput.append(userData).append("\n");

        // 插入数据库
        Chart chart = new Chart();
        chart.setStatus("wait");
        chart.setGoal(userGoal);
        chart.setChartData(userData);
        if (!StringUtils.isEmpty(name))
            chart.setName(name);
        if (!StringUtils.isEmpty(chartType))
            chart.setChartType(chartType);
        chart.setUserId(loginUser.getId());
        boolean saveResult = chartService.save(chart);
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "图表信息保存失败");
        if (!saveResult)
            handleChartUpdateError(chart.getId(), "图表初始数据保存失败");
        BiResponse biResponse = new BiResponse();
        biResponse.setChartId(chart.getId());
        long biModelId = CommonConstant.BI_MODEL_ID;
        // TODO:   用try catch解决任务队列满了抛异常
        CompletableFuture.runAsync(()->{
            Chart updateChart = new Chart();
            updateChart.setId(chart.getId());
            updateChart.setStatus("running");
            boolean bool = chartService.updateById(updateChart);
            if (!bool) {
                handleChartUpdateError(updateChart.getId(), "图表执行状态保存失败");
                return;
            }
            String result = aiManager.doChat(biModelId,userInput.toString());
            String[] splits = result.split("【【【【【");
            if (splits.length < 3)
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI生成错误");
            String genChart = splits[1].trim();
            String genResult = splits[2].trim();
            // Echarts代码过滤 "var option ="
            if (genChart.startsWith("var option =")) {
                // 去除 "var option ="
                genChart = genChart.replaceFirst("var\\s+option\\s*=\\s*", "");
            }
            Chart updateResult = new Chart();
            updateResult.setId(chart.getId());
            JsonObject chartJson;
            String genChartName;
            String updatedGenChart = "";
            try {
                chartJson = JsonParser.parseString(genChart).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "json代码解析异常");
            }
            // 自动添加图表类型
            if (StringUtils.isEmpty(chart.getName())) {
                JsonArray seriesArray = chartJson.getAsJsonArray("series");
                for (JsonElement i : seriesArray) {
                    String typeChart = i.getAsJsonObject().get("type").getAsString();
                    String CnChartType = chartService.getChartTypeToCN(typeChart);
                    updateResult.setChartType(CnChartType);
                }
            }
            // 自动加入图表名称结尾并设置图表名称
            if (StringUtils.isEmpty(chart.getName())) {
                try {
                    genChartName = String.valueOf(chartJson.getAsJsonObject("title").get("text"));
                } catch (JsonSyntaxException e) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "json代码不存在title字段");
                }
                genChartName = genChartName.replace("\"", "");
                if ( !genChartName.endsWith("图") && !genChartName.endsWith("表") && !genChartName.endsWith("图表"))
                    genChartName = genChartName + "图";
                updateResult.setName(genChartName);
                // 加入下载按钮
                JsonObject toolbox = new JsonObject();
                toolbox.addProperty("show", true);
                JsonObject saveAsImage = new JsonObject();
                saveAsImage.addProperty("show", true);
                saveAsImage.addProperty("excludeComponents", "['toolbox']");
                saveAsImage.addProperty("pixelRatio", 2);
                JsonObject feature = new JsonObject();
                feature.add("saveAsImage", saveAsImage);
                toolbox.add("feature", feature);
                chartJson.add("toolbox", toolbox);
                chartJson.remove("title");
                updatedGenChart = chartJson.toString();
            }
            updateResult.setGenChart(updatedGenChart);
            // TODO:枚举值实现
            updateResult.setStatus("succeed");
            boolean code = chartService.updateById(updateResult);
            if (!code)
                handleChartUpdateError(updateResult.getId(), "图表代码保存失败");
            biResponse.setGenChart(updatedGenChart);
            biResponse.setGenResult(genResult);
        }, threadPoolExecutor);
        return ResultUtils.success(biResponse);
    }

    /**
     * 文件AI分析
     *
     * @param multipartFile
     * @param genChartByAiRequest
     * @param request
     * @return
     */
    @PostMapping("/gen/async/mq")
    public BaseResponse<BiResponse> genChartByAiAsyncMq(@RequestPart("file") MultipartFile multipartFile,
                                                        GenChartByAiRequest genChartByAiRequest, HttpServletRequest request) {
        String name = genChartByAiRequest.getName();
        String goal = genChartByAiRequest.getGoal();
        String chartType = genChartByAiRequest.getChartType();
        // 校验
        ThrowUtils.throwIf(StringUtils.isNotBlank(name) && name.length() > 100, ErrorCode.PARAMS_ERROR, "名称过长");
        User loginUser = userService.getLoginUser(request);
        // 校验文件大小及后缀
        long size = multipartFile.getSize();
        final long TEN_MB = 10 * 1024 * 1024L;
        ThrowUtils.throwIf(size > TEN_MB, ErrorCode.PARAMS_ERROR, "文件大小大于10M");
        String fileName = multipartFile.getOriginalFilename();
        String suffix = FileUtil.getSuffix(fileName);
        final List<String> validFileSuffix = Arrays.asList("xlsx", "csv", "xls");
        ThrowUtils.throwIf(!validFileSuffix.contains(suffix), ErrorCode.PARAMS_ERROR, "文件后缀非法");
        // 每个用户限流
        redisLimiterManager.doRateLimit("genChartByAi" + loginUser.getId());
        // 构造用户输入
        StringBuilder userInput = new StringBuilder();
        userInput.append("分析需求：\n");
        // 拼接分析目标
        String userGoal = "请帮我合理的分析一下数据";
        if (StringUtils.isNotBlank(goal))
            userGoal = goal;
        // 分析输入加入图表类型
        if (StringUtils.isNotBlank(chartType))
            userGoal += ",请使用" + chartType;
        userInput.append(userGoal).append("\n");
        userInput.append("原始数据：\n");
        // 压缩数据
        String userData = ExcelUtils.excel2Csv(multipartFile);
        userInput.append(userData).append("\n");
        // 插入数据库
        Chart chart = new Chart();
        chart.setStatus("wait");
        chart.setGoal(userGoal);
        chart.setChartData(userData);
        if (!StringUtils.isEmpty(name))
            chart.setName(name);
        if (!StringUtils.isEmpty(chartType))
            chart.setChartType(chartType);
        chart.setUserId(loginUser.getId());
        taskManager.startTaskTimer(loginUser.getId());
        boolean saveResult = chartService.save(chart);
        if (!saveResult)
            handleChartUpdateError(chart.getId(), "图表初始数据保存失败");
        long newChartId = chart.getId();
        messageProducer.sendMessage(String.valueOf(newChartId));
        BiResponse biResponse = new BiResponse();
        biResponse.setChartId(newChartId);
        return ResultUtils.success(biResponse);
    }

    /**
     * 分析错误重试
     *
     * @param id
     * @return
     */
    @GetMapping("/gen/async/mq/retry")
    public BaseResponse<BiResponse> retryGenChartByAiAsyncMq(@RequestParam Long id) {
        log.info(String.valueOf(id));
        Chart byId = chartService.getById(id);
        String userGoal = byId.getGoal();
        String userData = byId.getChartData();
        String userStatus = byId.getStatus();
        // 防止多次重试
        if (StringUtils.equals(userStatus, "wait"))
            return null;
        // 构造用户输入
        StringBuilder userInput = new StringBuilder();
        userInput.append("分析需求：\n");
        // 拼接分析目标
        userInput.append(userGoal).append("\n");
        userInput.append("原始数据：\n");
        userInput.append(userData).append("\n");
        // 插入数据库
        Chart chart = new Chart();
        chart.setStatus("wait");
        chart.setId(id);
        taskManager.startTaskTimer(id);
        boolean saveResult = chartService.updateById(chart);
        if (!saveResult)
            handleChartUpdateError(chart.getId(), "图表状态更新失败");
        long newChartId = chart.getId();
        messageProducer.sendMessage(String.valueOf(newChartId));
        BiResponse biResponse = new BiResponse();
        biResponse.setChartId(newChartId);
        return ResultUtils.success(biResponse);
    }
    /**
     * 创建
     *
     * @param chartAddRequest
     * @param request
     * @return
     */
    @PostMapping("/add")
    public BaseResponse<Long> addchart(@RequestBody ChartAddRequest chartAddRequest, HttpServletRequest request) {
        if (chartAddRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart chart = new Chart();
        BeanUtils.copyProperties(chartAddRequest, chart);
        User loginUser = userService.getLoginUser(request);
        chart.setUserId(loginUser.getId());
        boolean result = chartService.save(chart);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        long newchartId = chart.getId();
        return ResultUtils.success(newchartId);
    }

    /**
     * 删除
     *
     * @param deleteRequest
     * @param request
     * @return
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deletechart(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = userService.getLoginUser(request);
        long id = deleteRequest.getId();
        // 判断是否存在
        Chart oldchart = chartService.getById(id);
        ThrowUtils.throwIf(oldchart == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可删除
        if (!oldchart.getUserId().equals(user.getId()) && !userService.isAdmin(request)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean b = chartService.removeById(id);
        return ResultUtils.success(b);
    }

    /**
     * 更新（仅管理员）
     *
     * @param chartUpdateRequest
     * @return
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updatechart(@RequestBody ChartUpdateRequest chartUpdateRequest) {
        if (chartUpdateRequest == null || chartUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart chart = new Chart();
        BeanUtils.copyProperties(chartUpdateRequest, chart);
        long id = chartUpdateRequest.getId();
        // 判断是否存在
        Chart oldchart = chartService.getById(id);
        ThrowUtils.throwIf(oldchart == null, ErrorCode.NOT_FOUND_ERROR);
        boolean result = chartService.updateById(chart);
        return ResultUtils.success(result);
    }

    /**
     * 根据 id 获取
     *
     * @param id
     * @return
     */
    @GetMapping("/get")
    public BaseResponse<Chart> getchartById(long id, HttpServletRequest request) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart chart = chartService.getById(id);
        if (chart == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        return ResultUtils.success(chart);
    }

    /**
     * 分页获取列表（封装类）
     *
     * @param chartQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/list/page")
    public BaseResponse<Page<Chart>> listchartByPage(@RequestBody ChartQueryRequest chartQueryRequest,
                                                     HttpServletRequest request) {
        long current = chartQueryRequest.getCurrent();
        long size = chartQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        Page<Chart> chartPage = chartService.page(new Page<>(current, size),
                getQueryWrapper(chartQueryRequest));
        return ResultUtils.success(chartPage);
    }

    /**
     * 分页获取当前用户创建的资源列表
     *
     * @param chartQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/my/list/page")
    public BaseResponse<Page<Chart>> listMychartByPage(@RequestBody ChartQueryRequest chartQueryRequest,
                                                       HttpServletRequest request) {
        if (chartQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        chartQueryRequest.setUserId(loginUser.getId());
        long current = chartQueryRequest.getCurrent();
        long size = chartQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        String cacheKey = "ChartController_listMyChartVOByPage_" + chartQueryRequest.getId();
        Page<Chart> cachedChartPage = redisCacheManager.getCachedResult(cacheKey);
        if (cachedChartPage != null) {
            // 缓存命中，比较缓存数据与数据库数据是否一致
            Page<Chart> databaseChartPage = chartService.page(new Page<>(current, size),
                    getQueryWrapper(chartQueryRequest));
            if (!chartService.isSamePage(cachedChartPage, databaseChartPage)) {
                redisCacheManager.putCachedResult(cacheKey, databaseChartPage);
            }
            return ResultUtils.success(cachedChartPage);
        } else {
            // 缓存未命中，从数据库中查询数据，并放入缓存
            Page<Chart> chartPage = chartService.page(new Page<>(current, size),
                    getQueryWrapper(chartQueryRequest));
            redisCacheManager.asyncPutCachedResult(cacheKey, chartPage);
            return ResultUtils.success(chartPage);
        }
    }


    /**
     * 编辑（用户）
     *
     * @param chartEditRequest
     * @param request
     * @return
     */
    @PostMapping("/edit")
    public BaseResponse<Boolean> editchart(@RequestBody ChartEditRequest chartEditRequest, HttpServletRequest request) {
        if (chartEditRequest == null || chartEditRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Chart chart = new Chart();
        BeanUtils.copyProperties(chartEditRequest, chart);
        User loginUser = userService.getLoginUser(request);
        long id = chartEditRequest.getId();
        // 判断是否存在
        Chart oldchart = chartService.getById(id);
        ThrowUtils.throwIf(oldchart == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可编辑
        if (!oldchart.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean result = chartService.updateById(chart);
        return ResultUtils.success(result);
    }

    /**
     * 获取查询包装类
     *
     * @param chartQueryRequest
     * @return
     */
    private QueryWrapper<Chart> getQueryWrapper(ChartQueryRequest chartQueryRequest) {
        QueryWrapper<Chart> queryWrapper = new QueryWrapper<>();
        if (chartQueryRequest == null) {
            return queryWrapper;
        }
        Long id = chartQueryRequest.getId();
        String goal = chartQueryRequest.getGoal();
        String chartType = chartQueryRequest.getChartType();
        String name = chartQueryRequest.getName();
        Long userId = chartQueryRequest.getUserId();
        String sortField = chartQueryRequest.getSortField();
        String sortOrder = chartQueryRequest.getSortOrder();
        // 当我进行图表查询时
        if(StringUtils.isNotBlank(goal) && StringUtils.isNotBlank(name) && StringUtils.isNotBlank(chartType))
            queryWrapper.and(wrapper -> wrapper
                            .like(StringUtils.isNotBlank(goal), "goal", "%" + goal + "%")
                            .or()
                            .like(StringUtils.isNotBlank(name), "name", "%" + name + "%")
                            .or()
                            .like(StringUtils.isNotBlank(chartType), "chartType", "%" + chartType + "%"))
                    .eq(ObjectUtils.isNotEmpty(userId), "userId", userId)
                    .eq("isDelete", false)
                    .orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                            sortField);
        else{
            queryWrapper.eq(id != null && id > 0, "id", id);
            queryWrapper.like(StringUtils.isNotBlank(goal), "goal", goal);
            queryWrapper.like(StringUtils.isNotBlank(name), "name", name);
            queryWrapper.like(StringUtils.isNotBlank(chartType), "chartType", chartType);
            queryWrapper.eq(ObjectUtils.isNotEmpty(userId), "userId", userId);
            queryWrapper.eq("isDelete", false);
            queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                    sortField);
        }
        return queryWrapper;
    }
}