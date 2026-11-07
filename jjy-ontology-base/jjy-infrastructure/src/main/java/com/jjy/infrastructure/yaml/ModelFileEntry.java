package com.jjy.infrastructure.yaml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * manifest.json 中 model_files 的一个条目。
 *
 * @author jjy-base
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelFileEntry {
    /** 文件名（相对 classpath:models/） */
    private String file;
    /** SHA-256 十六进制摘要（小写），null 表示未校验 */
    private String sha256;
}
