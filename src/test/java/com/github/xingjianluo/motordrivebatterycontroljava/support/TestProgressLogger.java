package com.github.xingjianluo.motordrivebatterycontroljava.support;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局用例进度扩展（ServiceLoader 自动注册，见
 * META-INF/services/org.junit.jupiter.api.extension.Extension）：
 * 每个测试方法执行前后打印统一的开始/结果标记，使单测日志能直接看出
 * 覆盖了哪些业务类别、每个用例通过还是失败，不依赖任何具体测试类。
 */
public class TestProgressLogger
        implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    /** 测试类 -> 该类覆盖的业务域（只影响日志展示）。 */
    private static final Map<String, String> BUSINESS_AREAS = new LinkedHashMap<>();
    static {
        BUSINESS_AREAS.put("LeaseManagerTest", "租约：有效期/到期自动回收/续期/回收后不复活/并发到期竞争/重启重放/停机联动/后台回收");
        BUSINESS_AREAS.put("ConcurrentLedgerTest", "并发：受理裁剪冻结/撤销幂等/结算只扣一次/文件日志重放");
        BUSINESS_AREAS.put("PowerArbitrationTest", "仲裁：全额受理/逆变器硬上限/容量裁剪/电流裁剪/重复请求/累计不超额");
        BUSINESS_AREAS.put("FaultStateMachineTest", "故障：过流停机/过温降级迟滞/欠压恢复裕量/传感器无效/不自行复位");
        BUSINESS_AREAS.put("PowerControlApiTest", "端到端HTTP：传感/受理/硬上限拒绝/结算/状态查询/带租约受理与续期/到期自动回收与容量复用（用例间上下文隔离）");
        BUSINESS_AREAS.put("ApplicationTests", "装配：Spring 上下文加载");
    }

    private static String areaOf(Class<?> cls) {
        return BUSINESS_AREAS.getOrDefault(cls.getSimpleName(), "其它");
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        Class<?> cls = context.getRequiredTestClass();
        Method m = context.getRequiredTestMethod();
        Logger log = LoggerFactory.getLogger(cls);
        log.info("======== [用例开始] 业务域=[{}] 用例={} ========", areaOf(cls), m.getName());
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        Class<?> cls = context.getRequiredTestClass();
        Method m = context.getRequiredTestMethod();
        Logger log = LoggerFactory.getLogger(cls);
        Throwable failure = context.getExecutionException().orElse(null);
        if (failure == null) {
            log.info("======== [用例通过] 业务域=[{}] 用例={} ========", areaOf(cls), m.getName());
        } else {
            log.error("======== [用例失败] 业务域=[{}] 用例={} 原因={}: {} ========",
                    areaOf(cls), m.getName(),
                    failure.getClass().getSimpleName(), failure.getMessage());
        }
    }
}
