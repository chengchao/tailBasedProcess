package com.ayang818.middleware.tailbase.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author 杨丰畅
 * @description TODO
 * @date 2020/5/22 22:00
 **/
public class DataStorage {
    public static List<Map<String, Set<String>>> BUCKET_TRACE_LIST = new ArrayList<>();

    public static List<Object> lockList = new ArrayList<>();
}