package com.yj.kedaya.scoring;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yj.kedaya.manager.AiManager;
import com.yj.kedaya.model.dto.question.QuestionAnswerDTO;
import com.yj.kedaya.model.dto.question.QuestionContentDTO;
import com.yj.kedaya.model.entity.App;
import com.yj.kedaya.model.entity.Question;
import com.yj.kedaya.model.entity.ScoringResult;
import com.yj.kedaya.model.entity.UserAnswer;
import com.yj.kedaya.model.vo.QuestionVO;
import com.yj.kedaya.service.QuestionService;
import com.yj.kedaya.service.ScoringResultService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AI 自定义测评类应用评分策略
 *
 *
 */
@Slf4j
@ScoringStrategyConfig(appType = 1, scoringStrategy = 1)
public class AiTestScoringStrategy implements ScoringStrategy {

    // 注入QuestionService实例
    @Resource
    private QuestionService questionService;

    // 注入AiManager实例
    @Resource
    private AiManager aiManager;

    @Resource
    private RedissonClient redissonClient;



    private static final String AI_ANSWER_LOCK = "AI_ANSWER_LOCK";

    /**
     * AI评分缓存
     */

    // 创建一个基于Caffeine缓存的Map，用于存储答案
    private final Cache<String, String> answerCacheMap =
            Caffeine.newBuilder().initialCapacity(1024)
                    // 缓存5分钟移除
                    .expireAfterAccess(5L, TimeUnit.MINUTES)
                    .build();



    /**
     * AI 评分系统消息
     */
    private static final String AI_TEST_SCORING_SYSTEM_MESSAGE = "你是一位严谨的判题专家，我会给你如下信息：\n" +
            "```\n" +
            "应用名称，\n" +
            "【【【应用描述】】】，\n" +
            "题目和用户回答的列表：格式为 [{\"title\": \"题目\",\"answer\": \"用户回答\"}]\n" +
            "```\n" +
            "\n" +
            "请你根据上述信息，按照以下步骤来对用户进行评价：\n" +
            "1. 要求：需要给出一个明确的评价结果，包括评价名称（尽量简短）和评价描述（尽量详细，大于 200 字）\n" +
            "2. 严格按照下面的 json 格式输出评价名称和评价描述\n" +
            "```\n" +
            "{\"resultName\": \"评价名称\", \"resultDesc\": \"评价描述\"}\n" +
            "```\n" +
            "3. 返回格式必须为 JSON 对象";




    /**
     * @param choices
     * @param app
     * @return {@link UserAnswer }
     * @throws Exception
     */
    @Override
    public UserAnswer doScore(List<String> choices, App app) throws Exception {
        Long appId = app.getId();
        String jsonStr = JSONUtil.toJsonStr(choices);
        String cacheKey = buildCacheKey(appId,jsonStr);
        String answerJson = answerCacheMap.getIfPresent(cacheKey);
        // 命中缓存则直接返回结果
        if (StrUtil.isNotBlank(answerJson)) {
            UserAnswer userAnswer = JSONUtil.toBean(answerJson, UserAnswer.class);
            userAnswer.setAppId(appId);
            userAnswer.setAppType(app.getAppType());
            userAnswer.setScoringStrategy(app.getScoringStrategy());
            userAnswer.setChoices(jsonStr);
            return userAnswer;
        }
        //定义锁
        RLock lock = redissonClient.getLock(AI_ANSWER_LOCK + cacheKey);

        try {
            // 竞争分布式锁，等待 3 秒，15 秒自动释放
            boolean res = lock.tryLock(3, 15, TimeUnit.SECONDS);

            //没抢到锁的就强行返回
            if (!res){
                return null;
            }
            // 抢到锁的业务才能执行 AI 调用

            // 1. 根据 id 查询到题目
            Question question = questionService.getOne(
                    Wrappers.lambdaQuery(Question.class).eq(Question::getAppId, appId)
            );
            QuestionVO questionVO = QuestionVO.objToVo(question);
            List<QuestionContentDTO> questionContent = questionVO.getQuestionContent();

            // 2. 调用 AI 获取结果
            // 封装 Prompt
            String userMessage = getAiTestScoringUserMessage(app, questionContent, choices);
            // AI 生成
            String result = aiManager.doSyncStableRequest(AI_TEST_SCORING_SYSTEM_MESSAGE, userMessage);

            // 提取 message.content 中的 JSON 字符串
            JSONObject jsonResponse = JSONUtil.parseObj(result);
            String content = jsonResponse.getByPath("message.content", String.class);
            log.warn("原生AI评分结果:\n {}",content);

            //JSON处理规则
            String cleanContent = content
                    .replace("```json", "")
                    .replace("\n", "")         // 移除换行符
                    .replace("\\n", "")       // 移除转义换行符
                    .replace("\\\"", "\"")    // 替换转义引号
                    .replace("\\", "")        // 移除反斜杠
                    .replaceAll("\\s+", " ")  // 替换多余空格为一个空格
                    .trim();

            // 截取需要的 JSON 信息
            int start = cleanContent.indexOf("{");
            int end = cleanContent.lastIndexOf("}");
            String json = cleanContent.substring(start, end + 1);

            // 如果需要，可以进一步处理json字符串以确保它是有效的
            if (json.endsWith(",")) {
                json = json.substring(0, json.length() - 1); // 去除可能的尾逗号
            }

            log.warn("处理过的AI评分结果:\n {}",json);

            // 缓存结果
            answerCacheMap.put(cacheKey,json);

            // 3. 构造返回值，填充答案对象的属性
            UserAnswer userAnswer = JSONUtil.toBean(json, UserAnswer.class);
            log.info("应用描述{}",userAnswer.getResultDesc());

            userAnswer.setAppId(appId);
            userAnswer.setAppType(app.getAppType());
            userAnswer.setScoringStrategy(app.getScoringStrategy());
            userAnswer.setChoices(JSONUtil.toJsonStr(choices));
            return userAnswer;




        } finally {
            if (lock != null && lock.isLocked()) {
                //锁必须自己本人才可以解
                if(lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }



    }

    /**
     * AI 评分用户消息封装
     *
     * @param app
     * @param questionContentDTOList
     * @param choices
     * @return
     */
    // 私有方法getAiTestScoringUserMessage，接收三个参数：App app，List<QuestionContentDTO> questionContentDTOList，List<String> choices
    private String getAiTestScoringUserMessage(App app, List<QuestionContentDTO> questionContentDTOList, List<String> choices) {
        // 定义StringBuilder类型的变量userMessage，用于拼接字符串
        StringBuilder userMessage = new StringBuilder();
        // 将app的appName和appDesc拼接到userMessage中
        userMessage.append(app.getAppName()).append("\n");
        userMessage.append(app.getAppDesc()).append("\n");
        // 定义一个空的ArrayList，用于存放QuestionAnswerDTO类型的变量questionAnswerDTOList

        List<QuestionAnswerDTO> questionAnswerDTOList = new ArrayList<>();
        // 遍历questionContentDTOList，将choices中的答案拼接到questionAnswerDTO中

        for (int i = 0; i < questionContentDTOList.size(); i++) {
            QuestionAnswerDTO questionAnswerDTO = new QuestionAnswerDTO();
            questionAnswerDTO.setTitle(questionContentDTOList.get(i).getTitle());
            questionAnswerDTO.setUserAnswer(choices.get(i));
            // 将questionAnswerDTO添加到questionAnswerDTOList中

            questionAnswerDTOList.add(questionAnswerDTO);
        }
        // 将questionAnswerDTOList转换为json字符串，拼接到userMessage中
        // 返回userMessage转换为字符串
        userMessage.append(JSONUtil.toJsonStr(questionAnswerDTOList));
        return userMessage.toString();
    }

    /**
     * @param appId
     * @param choicesStr
     * @return {@link String }
     */
    // 根据appId和choicesStr生成缓存key
    private String buildCacheKey(Long appId, String choicesStr) {
        // 将appId和choicesStr拼接
        return DigestUtil.md5Hex(appId + ":" + choicesStr);
        // 返回拼接后的字符串的MD5值
    }


}
