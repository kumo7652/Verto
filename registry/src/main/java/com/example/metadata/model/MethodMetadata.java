package com.example.metadata.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 方法元数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MethodMetadata {
    /**
     * 服务键名
     */
    private String serviceKey;

    /**
     * 方法名称
     */
    private String methodName;

    /**
     * 参数类型列表（全限定名）
     */
    private String[] parameterTypes;

    /**
     * 返回类型（全限定名）
     */
    private String returnType;

    /**
     * 方法描述
     */
    private String description;

    /**
     * 获取方法键名
     */
    public String getMethodKey() {
        StringBuilder sb = new StringBuilder(methodName);
        if (parameterTypes != null && parameterTypes.length > 0) {
            sb.append("(");
            sb.append(String.join(",", parameterTypes));
            sb.append(")");
        }
        return sb.toString();
    }
}