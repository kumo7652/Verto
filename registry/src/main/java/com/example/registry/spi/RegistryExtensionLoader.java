package com.example.registry.spi;

import cn.hutool.core.io.resource.ResourceUtil;
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
 * 注册中心模块专用 SPI 加载器
 */
@Slf4j
public class RegistryExtensionLoader {
    private static final Map<String, Map<String, Class<?>>> loaderMap = new ConcurrentHashMap<>();
    private static final Map<String, Object> instanceCache = new ConcurrentHashMap<>();

    private static final String[] SCAN_DIRS = new String[]{
            "META-INF/rpc/system/",
            "META-INF/rpc/custom/"
    };

    public static <T> Map<String, Class<?>> load(Class<T> loadClass) {
        log.info("加载类型为{}的spi", loadClass.getName());

        Map<String, Class<?>> keyMap = new HashMap<>();
        for (String dir : SCAN_DIRS) {
            List<URL> resources = ResourceUtil.getResources(dir + loadClass.getName());

            for (URL resource : resources) {
                try (BufferedReader bufferedReader = new BufferedReader(
                        new InputStreamReader(resource.openStream()))) {
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

    @SuppressWarnings("unchecked")
    public static <T> T getInstance(Class<T> clazz, String key) {
        String className = clazz.getName();
        Map<String, Class<?>> keyClassMap = loaderMap.get(className);

        if (keyClassMap == null) {
            load(clazz);
            keyClassMap = loaderMap.get(className);
        }

        if (keyClassMap == null || !keyClassMap.containsKey(key)) {
            throw new RuntimeException(String.format("未发现 %s 的 %s 实例", className, key));
        }

        Class<?> implClass = keyClassMap.get(key);
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