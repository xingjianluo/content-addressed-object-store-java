package com.github.xingjianluo.contentaddressedobjectstorejava.store;

import com.github.xingjianluo.contentaddressedobjectstorejava.model.ContentHash;
import com.github.xingjianluo.contentaddressedobjectstorejava.model.VersionInfo;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * 对象版本元数据存储。
 * 每个对象有一个指针文件（原子写入）指向当前版本，另有不可变的版本记录文件。
 */
public interface VersionStore {

    void init() throws IOException;

    /** 返回对象当前版本；不存在返回 empty。 */
    Optional<VersionInfo> head(String objectKey) throws IOException;

    /** 返回指定版本；不存在返回 empty。 */
    Optional<VersionInfo> version(String objectKey, long version) throws IOException;

    /** 返回对象全部历史版本，按版本号升序。 */
    List<VersionInfo> history(String objectKey) throws IOException;

    /** 全部对象名。 */
    List<String> listObjects() throws IOException;

    /**
     * 条件提交新版本。expectedVersion 为当前版本号；对象不存在时用 -1。
     * 必须原子地完成“检查 + 追加版本记录 + 推进指针”。
     */
    VersionInfo commitVersion(String objectKey, long expectedVersion,
                              ContentHash hash, long length) throws IOException;
}
