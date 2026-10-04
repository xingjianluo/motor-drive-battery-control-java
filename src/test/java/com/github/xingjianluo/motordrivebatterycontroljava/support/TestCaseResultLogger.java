package com.github.xingjianluo.motordrivebatterycontroljava.support;

import java.util.Optional;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用例结果统一打印：每个用例开始时打印“覆盖业务”，结束时明确打印“通过/失败”。
 * 配合各用例自身的业务流水日志（受理/裁剪/拒绝笔数、授予量、冻结量、可调度量、
 * 回收/续期等），跑一遍 mvn test 即可从控制台看出覆盖了哪些业务、每个用例过没过。
 */
public class TestCaseResultLogger implements BeforeTestExecutionCallback,
        AfterTestExecutionCallback {

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        Logger log = LoggerFactory.getLogger(context.getRequiredTestClass());
        log.info("==== 开始用例 [{}] ====", context.getDisplayName());
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        Logger log = LoggerFactory.getLogger(context.getRequiredTestClass());
        Optional<Throwable> error = context.getExecutionException();
        String name = context.getDisplayName();
        if (error.isPresent()) {
            log.error("==== 用例 [{}] 结果: 失败（{}）====", name, error.get());
        } else {
            log.info("==== 用例 [{}] 结果: 通过 ====", name);
        }
    }
}
