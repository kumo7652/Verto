package com.example.extension;

import cn.hutool.core.io.resource.ResourceUtil;
import com.example.constant.RpcConstant;
import com.example.fault.retry.RetryStrategy;
import com.example.fault.tolerant.TolerantStrategy;

import com.example.serializer.Serializer;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * spi加载器
 */
@Slf4j
public class ExtensionLoader {
    /**
     * 存储已加载的类 接口名 => (key => 实现类)
     */
    private static final Map<String, Map<String, Class<?>>> loaderMap = new ConcurrentHashMap<>();

    /**
     * 对象实例缓存
     */
    private static final Map<String, Object> instanceCache = new ConcurrentHashMap<>();

    /**
     * 系统扫描路径
     */
    private static final String[] SCAN_DIRS = new String[]{
            RpcConstant.RPC_SYSTEM_SPI_DIR,
            RpcConstant.RPC_CUSTOM_SPI_DIR
    };

    /**
     * 动态加载的类列表
     */
    private static final List<Class<?>> LOAD_CLASS_LIST = List.of(
            Serializer.class,
            RetryStrategy.class,
            TolerantStrategy.class
    );

    /**
     * 加载所有类型
     */
    public static void loadAll() {
       log.info("加载所有spi");
       for (Class<?> clazz : LOAD_CLASS_LIST) {
           load(clazz);
       }
    }

    /**
     * 加载某个类型
     */
    public static Map<String, Class<?>> load(Class<?> loadClass) {
         log.info("加载类型为{}的spi", loadClass.getName());

         // 扫描路径，用户目录优先于系统目录
         Map<String, Class<?>> keyMap = new HashMap<>();
         for (String dir : SCAN_DIRS) {
             List<URL> resources = ResourceUtil.getResources(dir + loadClass.getName());

             // 读取每个资源文件
             for (URL resource : resources) {
                 try {
                     InputStreamReader inputStreamReader = new InputStreamReader(resource.openStream());
                     BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

                     String line;
                     while ((line = bufferedReader.readLine()) != null) {
                         String[] strArray = line.split("=");
                         if (strArray.length == 2) {
                             String key = strArray[0];
                             String className = strArray[1];
                             keyMap.put(key, Class.forName(className));
                         }
                    }
                 } catch (Exception e) {
                    log.error(e.getMessage());
                }
            }
        }

        loaderMap.put(loadClass.getName(), keyMap);
        return keyMap;
    }

    /**
     * 获取某个接口的实例
     */
    @SuppressWarnings("unchecked")
    public static <T> T getInstance(Class<T> clazz, String key) {
        String className = clazz.getName();
        Map<String, Class<?>> keyClassMap = loaderMap.get(className);

        if (keyClassMap == null) {
            throw new RuntimeException(String.format("ExtensionLoader 未加载 %s 类型", className));
        }

        if (!keyClassMap.containsKey(key)) {
            throw new RuntimeException(String.format("ExtensionLoader 未发现有 %s 的实例", key));
        }

        // 获取到要加载的实现类型
        Class<?> implClass = keyClassMap.get(key);

        // 从实例缓存中加载该类型实例
        String implClassName = implClass.getName();
        if (!instanceCache.containsKey(implClassName)) {
            try {
                Constructor<?> implClassConstructor = implClass.getConstructor();
                instanceCache.put(implClassName, implClassConstructor.newInstance());


            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }

        return (T) instanceCache.get(implClassName);
    }
}
