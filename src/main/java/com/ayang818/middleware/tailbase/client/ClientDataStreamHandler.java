package com.ayang818.middleware.tailbase.client;

import com.alibaba.fastjson.JSON;
import com.ayang818.middleware.tailbase.BasicHttpHandler;
import com.ayang818.middleware.tailbase.Constants;
import com.ayang818.middleware.tailbase.common.Resp;
import com.ayang818.middleware.tailbase.utils.WsClient;
import com.google.common.collect.Sets;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.asynchttpclient.ws.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.ayang818.middleware.tailbase.client.DataStorage.*;

/**
 * @author 杨丰畅
 * @description client 端用于处理读入数据流，向 backend 发送对应请求的类
 * @date 2020/5/5 12:47
 **/
public class ClientDataStreamHandler implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(ClientDataStreamHandler.class);
    public static WebSocket websocket;
    // 行号
    private static volatile long lineCount = 0;
    // 大桶的 pos 不取余，每 Constants.UPDATE_INTERVAL 行，切换大桶
    private static volatile int pos = 0;
    // 小桶的 pos ，不需要取余, MAX < Constants.CLIENT_SMALL_BUCKET_SIZE,
    // 每读 Constants.SWITCH_SMALL_BUCKET_INTERVAL 行，切换小桶
    private static volatile int innerPos = 0;
    // pos % BIG_BUCKET_COUNT
    private static volatile int bigBucketPos = 0;
    private static volatile BigBucket bigBucket;
    private static volatile TraceIndexBucket traceIndexBucket;
    private static volatile List<byte[]> dataBucket;
    // 一个工作周期中记录所有errTraceId
    private static volatile Set<String> errTraceIdSet;
    // 上一个block最后一条不完整数据的起点（不包含|）
    private static volatile int lastPartLineStartPos;
    private static boolean isFin = false;

    private static final StringBuilder lineBuilder = new StringBuilder();

    public static void init() {
        for (int i = 0; i < Constants.CLIENT_BIG_BUCKET_COUNT; i++) {
            BigBucket bigBucket = new BigBucket();
            bigBucket.init();
            BUCKET_TRACE_LIST.add(bigBucket);
            DATA_LIST.add(new ArrayList<>(10));
        }
        HANDLER_THREAD_POOL = new ThreadPoolExecutor(1, 1, 30,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new DefaultThreadFactory("line-handler"),
                new ThreadPoolExecutor.AbortPolicy());

        UPDATE_THREAD = new ThreadPoolExecutor(2, 2, 60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1000), new DefaultThreadFactory("update_thread"));
    }

    public static void start() {
        START_POOL.execute(new ClientDataStreamHandler());
    }

    @Override
    public void run() {
        try {
            websocket = WsClient.getWebsocketClient();

            String path = getPath();
            // process data on client, not server
            if (path == null || "".equals(path)) {
                logger.warn("path is empty");
                return;
            }
            URL url = new URL(path);
            logger.info("data path:" + path);
            // fetch the data source
            HttpURLConnection httpConnection =
                    (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            InputStream input = httpConnection.getInputStream();
            ReadableByteChannel channel = Channels.newChannel(input);

            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(Constants.INPUT_BUFFER_SIZE);

            bigBucket = BUCKET_TRACE_LIST.get(bigBucketPos);
            errTraceIdSet = Sets.newHashSet();
            traceIndexBucket = bigBucket.getSmallBucket(innerPos);
            dataBucket = DATA_LIST.get(bigBucketPos);

            byte[] bytes;
            // marked as a working small bucket
            traceIndexBucket.tryEnter(1, 5, pos, innerPos);
            // use block read
            while (channel.read(byteBuffer) != -1) {
                ((Buffer) byteBuffer).flip();
                int remain = byteBuffer.remaining();
                bytes = new byte[remain];
                byteBuffer.get(bytes, 0, remain);
                ((Buffer) byteBuffer).clear();

                // HANDLER_THREAD_POOL.execute(new BlockWorker(bytes, remain));
                BlockWorker.run(bytes, remain);
            }
            isFin = true;
            // last update, clear the badTraceIdSet
            updateWrongTraceId();
            traceIndexBucket.quit();
            callFinish();
            input.close();
            logger.info("finish");
        } catch (Exception e) {
            logger.warn("拉取数据流的过程中产生错误！", e);
        }

    }

    /**
     * call backend to update the wrongTraceIdList
     */
    private static void updateWrongTraceId() {
        String json = JSON.toJSONString(errTraceIdSet);
        // send badTraceIdList and its pos to the backend
        String msg = String.format("{\"badTraceIdSet\": %s, \"pos\": %d}"
                , json, pos);
        updateDataQueue.offer(msg);

        if (updateDataQueue.size() >= 5 || isFin) {
            StringBuilder res = new StringBuilder();
            res.append("{");
            res.append(String.format("\"type\": %d,", Constants.UPDATE_TYPE)).append("\"data\": [");
            while (updateDataQueue.size() > 0) {
                String tmp = updateDataQueue.poll();
                res.append("'").append(tmp).append("'").append(",");
            }
            res.append("]");
            res.append("}");
            UPDATE_THREAD.execute(() -> websocket.sendTextFrame(res.toString()));
            logger.info("成功上报pos {} 前的wrongTraceId...", pos);
        }
        // auto clear after update
        errTraceIdSet.clear();
    }

    /**
     * 给定区间中的所有错误traceId和pos，拉取对应traceIds的spans
     *
     * @param wrongTraceIdSet
     * @param pos
     * @return
     */
    public static void getWrongTracing(Set<String> wrongTraceIdSet, int pos, List<Resp> res) {
        int bucketCount = Constants.CLIENT_BIG_BUCKET_COUNT;
        // calculate the three continue pos
        int curr = pos % bucketCount;
        int prev = (curr - 1 == -1) ? bucketCount - 1 : (curr - 1) % bucketCount;
        int next = (curr + 1 == bucketCount) ? 0 : (curr + 1) % bucketCount;

        logger.info(String.format("pos: %d, 开始收集 trace details curr: %d, prev: %d, next: %d，三个 " +
                "bucket中的数据", pos, curr, prev, next));

        Map<String, List<String>> wrongTraceMap = new HashMap<>(32);

        // these traceId data should be collect
        getWrongTraceWithBucketPos(prev, pos, wrongTraceIdSet, wrongTraceMap, true);
        getWrongTraceWithBucketPos(curr, pos, wrongTraceIdSet, wrongTraceMap, false);
        getWrongTraceWithBucketPos(next, pos, wrongTraceIdSet, wrongTraceMap, false);

        // logger.info(res);
        res.add(new Resp(pos, wrongTraceMap));
    }

    /**
     * @param bucketPos     需要选取的bucketPos
     * @param pos           main pos
     * @param errTraceIdSet
     * @param wrongTraceMap
     * @param shouldClear
     */
    private static void getWrongTraceWithBucketPos(int bucketPos, int pos, Set<String> errTraceIdSet
            , Map<String,
            List<String>> wrongTraceMap, boolean shouldClear) {
        // backend start pull these bucket
        BigBucket bigBucket = BUCKET_TRACE_LIST.get(bucketPos);
        List<TraceIndexBucket> traceIndexBucketList = bigBucket.getSmallBucketList();
        List<byte[]> tmpDataBucket = DATA_LIST.get(bucketPos);
        List<String> spans;
        int bucketCount = Constants.CLIENT_BIG_BUCKET_COUNT;
        int tmpBlockOffset;
        int tmpStartPos;
        int tmpEndPos;
        byte[] tmpBlock;

        for (TraceIndexBucket traceIndexBucket : traceIndexBucketList) {
            if (traceIndexBucket.tryEnter(50, 5, pos, innerPos)) {
                // 这里看起来像是O(n^2)的操作，但是实际上traceIdSet的大小基本都非常小
                for (String traceId : errTraceIdSet) {
                    List<int[]> spansIndexes = traceIndexBucket.getSpansIndex(traceId);
                    if (spansIndexes != null) {
                        for (int[] spansIndex : spansIndexes) {
                            tmpBlockOffset = spansIndex[0];
                            tmpStartPos = spansIndex[1];
                            tmpEndPos = spansIndex[2];

                            tmpBlock = tmpDataBucket.get(tmpBlockOffset);
                            // 说明在同一个block中，不需要跨block寻找
                            if (tmpStartPos <= tmpEndPos) {
                                spans = wrongTraceMap.computeIfAbsent(traceId, k -> new ArrayList<>());
                                spans.add(new String(tmpBlock, tmpStartPos, tmpEndPos - tmpStartPos));
                            } else {
                                // TODO 切的时候有问题
                                // 先收集本block中的内容
                                String firstPart = new String(tmpBlock, tmpStartPos, tmpBlock.length - tmpStartPos);
                                String secondPart;
                                String addedSpan;
                                // 判断是不是一个bucket中的block
                                if (tmpDataBucket.size() > tmpBlockOffset + 1) {
                                    byte[] bytes = tmpDataBucket.get(tmpBlockOffset + 1);
                                    secondPart = new String(bytes, 0, tmpEndPos);
                                } else {
                                    // 不是一个bucket中的
                                    List<byte[]> bytesList = DATA_LIST.get((bucketPos + 1) % Constants.CLIENT_BIG_BUCKET_COUNT);
                                    byte[] nextBlock = bytesList.get(0);
                                    secondPart = new String(nextBlock, 0, tmpEndPos);
                                }
                                addedSpan = firstPart + secondPart;
                                spans = wrongTraceMap.computeIfAbsent(traceId, k -> new ArrayList<>());
                                spans.add(addedSpan);
                            }
                        }
                    }
                }
            }
            if (shouldClear) {
                traceIndexBucket.clear();
            }
            traceIndexBucket.quit();
        }
        if (shouldClear) {
            DATA_LIST.get(bucketPos).clear();
        }
    }

    private void callFinish() {
        websocket.sendTextFrame(String.format("{\"type\": %d}", Constants.FIN_TYPE));
        logger.info("已发送 FIN 请求");
    }

    private String getPath() {
        String port = System.getProperty("server.port", "8080");
        String env = System.getProperty("server.env", "prod");
        if ("prod".equals(env)) {
            if (Constants.CLIENT_PROCESS_PORT1.equals(port)) {
                return "http://localhost:" + BasicHttpHandler.getDataSourcePort() + "/trace1.data";
            } else if (Constants.CLIENT_PROCESS_PORT2.equals(port)) {
                return "http://localhost:" + BasicHttpHandler.getDataSourcePort() + "/trace2.data";
            } else {
                return null;
            }
        } else {
            if (Constants.CLIENT_PROCESS_PORT1.equals(port)) {
                return "http://localhost:8080/trace1.data";
            } else if (Constants.CLIENT_PROCESS_PORT2.equals(port)) {
                return "http://localhost:8080/trace2.data";
            } else {
                return null;
            }
        }
    }

    /**
     * 处理读取出来的一块数据，作为一个worker直接执行
     */
    private static class BlockWorker {
        byte[] bytes;

        public BlockWorker(byte[] bytes) {
            this.bytes = bytes;
        }

        public static void run(byte[] bytes, int remain) {
            dataBucket.add(bytes);
            // 一行的开始位置
            int lineStartPos = 0;
            // 一行的结束位置
            int lineEndPos = -1;
            // traceId 结束位置
            int traceIdEndPos = 0;
            // tags 起始位置
            int tagsStartPos = 0;
            // | 计数，每换行重置0
            int Icount = 0;

            boolean isFirstLine = true;
            StringBuilder traceIdBuilder = new StringBuilder();
            StringBuilder tagsBuilder = new StringBuilder();
            byte spl = 124; // |
            byte lf = 10;   // \n
            for (int i = 0; i < remain; i++) {
                // 124 == |，分隔符
                if (bytes[i] == spl) {
                    Icount += 1;
                    if (Icount == 1) {
                        traceIdEndPos = i;
                    }
                    if (Icount == 8) {
                        tagsStartPos = i + 1;
                    }
                }
                // 10 == \n，换行
                if (bytes[i] == lf) {
                    lineStartPos = lineEndPos + 1;
                    lineEndPos = i;
                    lineCount += 1;
                    Icount = 0;

                    // 结合前一块最后部分，形成完整的一行，按照字符处理
                    if (isFirstLine) {
                        String linePart = new String(bytes, lineStartPos, lineEndPos - lineStartPos);
                        lineBuilder.append(linePart);
                        handleLine(lineBuilder.toString(), traceIdBuilder, tagsBuilder, lineEndPos);
                        isFirstLine = false;
                    } else {
                        // 用于处理连续行
                        String traceId = new String(bytes, lineStartPos, traceIdEndPos - lineStartPos);
                        String tags = new String(bytes, tagsStartPos, lineEndPos - tagsStartPos);
                        handleLine(traceId, tags, lineStartPos, lineEndPos);
                        traceIdEndPos = 0;
                        tagsStartPos = 0;
                    }

                    // 先切换大桶，再切小桶
                    if (lineCount % Constants.UPDATE_INTERVAL == 0) {
                        // 更新错误链路id到backend
                        updateWrongTraceId();

                        // blockOffset = -1;
                        pos = (int) lineCount / Constants.UPDATE_INTERVAL;
                        bigBucketPos = pos % Constants.CLIENT_BIG_BUCKET_COUNT;
                        // switch to next big bucket
                        bigBucket = BUCKET_TRACE_LIST.get(bigBucketPos);
                        // switch to next data bucket
                        dataBucket = DATA_LIST.get(bigBucketPos);
                        // 由于还在处理同一块block，所以还是需要再次添加
                        dataBucket.add(bytes);
                    }

                    // 在这里切换小桶，旧桶退出工作状态，新桶进入工作状态
                    if (lineCount % Constants.SWITCH_SMALL_BUCKET_INTERVAL == 0) {
                        traceIndexBucket.quit();
                        if (innerPos == Constants.CLIENT_SMALL_BUCKET_COUNT - 1) {
                            innerPos = 0;
                        } else {
                            innerPos += 1;
                        }
                        traceIndexBucket = bigBucket.getSmallBucket(innerPos);
                        if (!traceIndexBucket.tryEnter(100, 10, pos, innerPos)) {
                            traceIndexBucket.clear();
                            traceIndexBucket.forceEnter();
                            logger.warn("强制清空 pos {} innerPos {} 处的数据", pos, innerPos);
                        }
                    }
                }
            }
            // 将最后一部分不完整行导入lineBuilder
            if (remain - 1 >= lineEndPos + 1) {
                lineBuilder.append(new String(bytes, lineEndPos + 1, remain - lineEndPos - 1));
                lastPartLineStartPos = lineEndPos + 1;
            } else {
                lastPartLineStartPos = remain;
            }
        }

        /**
         * 性能瓶颈，但是显然不知道怎么优化
         */
        private static void handleLine(String traceId, String tags, int startPos, int endPos) {
            // TODO 不再维护完整的数据，而是维护索引
            List<int[]> spanList = traceIndexBucket.computeIfAbsent(traceId);
            // 索引内容包含 1.bucket内部所在byte[]的dataOffset 2. startPos 3. endPos (含左不含右)
            // if startPos > endPos, 说明此行跨block
            if (dataBucket.size() == 0) return ;
            spanList.add(new int[]{dataBucket.size() - 1, startPos, endPos});

            if (tags.contains("error=1") || (tags.contains("http.status_code=") && !tags.contains("http.status_code=200"))) {
                errTraceIdSet.add(traceId);
            }
        }

        /**
         * <p>处理块与块之间交界处的合并行</p>
         *
         * @param line
         * @param traceIdBuilder
         * @param tagsBuilder
         * @param firstPartLineEndPos
         */
        public static void handleLine(String line, StringBuilder traceIdBuilder, StringBuilder tagsBuilder, int firstPartLineEndPos) {
            char[] lineChars = line.toCharArray();
            int len = lineChars.length;
            // 由于只需要第一部分和最后一部分，所以这样从头找和从结尾找会快很多
            for (int i = 0; i < len; i++) {
                // 找id
                if (lineChars[i] == '|') {
                    traceIdBuilder.append(lineChars, 0, i);
                    break;
                }
            }

            for (int i = len - 1; i >= 0; i--) {
                // 找tags
                if (lineChars[i] == '|') {
                    tagsBuilder.append(lineChars, i + 1, len - (i + 1));
                    break;
                }
            }

            String traceId = traceIdBuilder.toString();
            String tags = tagsBuilder.toString();

            List<int[]> tmpIndexList = traceIndexBucket.computeIfAbsent(traceId);
            // 发送上一个block的位置
            int prePos;
            if (dataBucket.size() - 2 < 0) {
                int preBucketPos = (bigBucketPos - 1) < 0 ? Constants.CLIENT_BIG_BUCKET_COUNT - 1 : bigBucketPos % Constants.CLIENT_BIG_BUCKET_COUNT;
                prePos = DATA_LIST.get(preBucketPos).size() - 1;
                if (prePos < 0) {
                    logger.warn("preBucketPos {} 已经被清除...", preBucketPos);
                    return ;
                }
            } else {
                prePos = dataBucket.size() - 2;
            }
            tmpIndexList.add(new int[]{prePos, lastPartLineStartPos, firstPartLineEndPos});

            if (tags.contains("error=1") || (tags.contains("http.status_code=") && !tags.contains("http.status_code=200"))) {
                errTraceIdSet.add(traceId);
            }
            // 清空，方便重用
            traceIdBuilder.delete(0, traceIdBuilder.length());
            tagsBuilder.delete(0, tagsBuilder.length());
            lineBuilder.delete(0, lineBuilder.length());
        }
    }
}