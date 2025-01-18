package com.yj.kedaya.scoring;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yj.kedaya.manager.AiManager;
import com.yj.kedaya.model.dto.question.QuestionAnswerDTO;
import com.yj.kedaya.model.dto.question.QuestionContentDTO;
import com.yj.kedaya.model.entity.App;
import com.yj.kedaya.model.entity.Question;
import com.yj.kedaya.model.entity.UserAnswer;
import com.yj.kedaya.model.vo.QuestionVO;
import com.yj.kedaya.service.QuestionService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 得分类应用的AI评分策略
 */
@ScoringStrategyConfig(appType = 0, scoringStrategy = 1)
public class
AIScoreScoringStrategy implements ScoringStrategy {

    @Resource
    private QuestionService questionService;

    @Resource
    private AiManager aiManager;

    @Resource
    private RedissonClient redissonClient;

    private static final String AI_ANSWER_LOCK = "AI_CUSTOM_SCORE_LOCK";

    private final Cache<String, String> answerCacheMap = Caffeine.newBuilder()
            .initialCapacity(1024)
            .expireAfterAccess(5L, TimeUnit.MINUTES)
            .build();

    private static final String AI_CUSTOM_SCORE_SCORING_SYSTEM_MESSAGE = "你是一位严谨的判题专家，我会给你如下信息：\n" +
            "```\n" +
            "应用名称，\n" +
            "【【【应用描述】】】，\n" +
            "题目和用户回答的列表：格式为 [{\"title\": \"题目\",\"userAnswer\": \"用户回答\",\"score\": \"正确答案得分\"}]\n" +
            "```\n" +
            "\n" +
            "请你根据上述信息，按照以下步骤给出题目的评分结果：\n" +
            "1. 要求：需要给出一个明确的得分结果，包括结果分数（尽量简短）和结果描述（尽量详细，大于 200 字）\n" +
            "2. 严格按照下面的 json 格式输出评价名称和评价描述\n" +
            "```\n" +
            "{\"resultScore\": \"结果分数\", \"resultScoreRange\": \"结果分数范围\"}\n" +
            "```\n" +
            "3. 返回格式必须为 JSON 对象";

    @Override
    public UserAnswer doScore(List<String> choices, App app) throws Exception {
        Long appId = app.getId();
        String jsonStr = JSONUtil.toJsonStr(choices);
        String cacheKey = buildCacheKey(appId, jsonStr);
        String answerJson = answerCacheMap.getIfPresent(cacheKey);

        if (StrUtil.isNotBlank(answerJson)) {
            UserAnswer userAnswer = JSONUtil.toBean(answerJson, UserAnswer.class);
            userAnswer.setAppId(appId);
            userAnswer.setAppType(app.getAppType());
            userAnswer.setScoringStrategy(app.getScoringStrategy());
            userAnswer.setChoices(jsonStr);
            return userAnswer;
        }

        RLock lock = redissonClient.getLock(AI_ANSWER_LOCK + cacheKey);

        try {
            boolean res = lock.tryLock(3, 15, TimeUnit.SECONDS);
            if (!res) {
                return null;
            }

            Question question = questionService.getOne(
                    Wrappers.lambdaQuery(Question.class).eq(Question::getAppId, appId)
            );
            QuestionVO questionVO = QuestionVO.objToVo(question);
            List<QuestionContentDTO> questionContent = questionVO.getQuestionContent();

            String userMessage = getAiCustomScoreScoringUserMessage(app, questionContent, choices);
            String result = aiManager.doSyncStableRequest(AI_CUSTOM_SCORE_SCORING_SYSTEM_MESSAGE, userMessage);

            int start = result.indexOf("{");
            int end = result.lastIndexOf("}");
            String json = result.substring(start, end + 1);

            answerCacheMap.put(cacheKey, json);

            UserAnswer userAnswer = JSONUtil.toBean(json, UserAnswer.class);
            userAnswer.setAppId(appId);
            userAnswer.setAppType(app.getAppType());
            userAnswer.setScoringStrategy(app.getScoringStrategy());
            userAnswer.setChoices(jsonStr);
            userAnswer.setResultScore(JSONUtil.parseObj(json).getInt("totalScore"));

            return userAnswer;
        } finally {
            if (lock != null && lock.isLocked() && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }


    private String getAiCustomScoreScoringUserMessage(App app, List<QuestionContentDTO> questionContentDTOList, List<String> choices) {
        StringBuilder userMessage = new StringBuilder();
        userMessage.append(app.getAppName()).append("\n");
        userMessage.append("【【【").append(app.getAppDesc()).append("】】】\n"); // 按prompt要求修改

        List<QuestionAnswerDTO> questionAnswerDTOList = new ArrayList<>();
        for (int i = 0; i < questionContentDTOList.size(); i++) {
            QuestionAnswerDTO questionAnswerDTO = new QuestionAnswerDTO();
            questionAnswerDTO.setTitle(questionContentDTOList.get(i).getTitle());
            questionAnswerDTO.setUserAnswer(choices.get(i));


            int score = 0;
            for (QuestionContentDTO.Option option : questionContentDTOList.get(i).getOptions()) {
                if (option.getKey().equals(choices.get(i))) {
                    score = Optional.of(option.getScore()).orElse(0);
                    break;
                }
            }
            questionAnswerDTO.setUserAnswer(choices.get(i));



            questionAnswerDTOList.add(questionAnswerDTO);
        }
        userMessage.append(JSONUtil.toJsonStr(questionAnswerDTOList));
        return userMessage.toString();
    }



    private String buildCacheKey(Long appId, String choicesStr) {
        return DigestUtil.md5Hex(appId + ":" + choicesStr);
    }
}