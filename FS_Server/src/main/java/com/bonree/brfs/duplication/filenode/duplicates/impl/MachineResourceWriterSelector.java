package com.bonree.brfs.duplication.filenode.duplicates.impl;

import com.bonree.brfs.common.utils.Pair;
import com.bonree.brfs.duplication.datastream.connection.DiskNodeConnection;
import com.bonree.brfs.duplication.datastream.connection.DiskNodeConnectionPool;
import com.bonree.brfs.duplication.filenode.FileNodeStorer;
import com.bonree.brfs.duplication.filenode.duplicates.ServiceSelector;
import com.bonree.brfs.email.EmailPool;
import com.bonree.brfs.resourceschedule.model.LimitServerResource;
import com.bonree.brfs.resourceschedule.model.ResourceModel;
import com.bonree.mail.worker.MailWorker;
import com.bonree.mail.worker.ProgramInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 服务写数据服务选择策略 按磁盘剩余量选择服务，选择服务只会选择不同ip的，可能出现选出的节点数与需求的长度不一致
 */
public class MachineResourceWriterSelector implements ServiceSelector{
    private static final Logger LOG = LoggerFactory.getLogger(MachineResourceWriterSelector.class);
    private DiskNodeConnectionPool connectionPool = null;
    private String groupName;
    private int centSize;
    private LimitServerResource limit;
    private FileNodeStorer storer;
    private long fileSize = 0;
    public MachineResourceWriterSelector(DiskNodeConnectionPool connectionPool,FileNodeStorer storer, LimitServerResource limit, String groupName, long fileSize,int centSize){
        this.connectionPool = connectionPool;
        this.storer =storer;
        this.groupName = groupName;
        this.centSize = centSize;
        this.limit = limit;
        this.fileSize = fileSize;
    }
    @Override
    public Collection<ResourceModel> filterService(Collection<ResourceModel> resourceModels, String path){
        // 无资源
        if(resourceModels == null|| resourceModels.isEmpty()){
            return null;
        }
        Set<ResourceModel> wins = new HashSet<>();
        long diskRemainSize;
        int numSize = this.storer.fileNodeSize();
        for(ResourceModel resourceModel : resourceModels){
            diskRemainSize = resourceModel.getLocalRemainSizeValue(path) - numSize *fileSize;
            if(diskRemainSize < this.limit.getRemainForceSize()){
                LOG.warn("sn: {} remainsize: {}, force:{} !! will refused",path,diskRemainSize,this.limit.getRemainForceSize());
                continue;
            }
            if(diskRemainSize <this.limit.getRemainWarnSize()){
                LOG.warn("sn: {} remainsize: {}, force:{} !! will full",path,diskRemainSize,this.limit.getRemainWarnSize());
            }
            wins.add(resourceModel);
        }
        return wins;
    }

    @Override
    public Collection<ResourceModel> selector(Collection<ResourceModel> resources, String path, int num){
        if(resources == null || resources.isEmpty()){
            return  null;
        }
        // 如果可选服务少于需要的，发送报警邮件
        int resourceSize = resources.size();
        boolean lessFlag = resourceSize <= num;
        if(lessFlag){
            sendSelectEmail(resources,path,num);
            return resources;
        }
        // 转换为Map
        Map<String,ResourceModel> map = convertResourceMap(resources);
        // 转换为权重值
        List<Pair<String, Integer>>intValues =  covertValues(resources,path,centSize);
        List<ResourceModel> wins = selectNode(this.connectionPool,map,intValues,this.groupName,num);
        int winSize = wins.size();
        // 若根据ip分配的个数满足要求，则返回该集合，不满足则看是否为
        if(winSize == num){
            return  wins;
        }
        Set<String> sids = selectWins(wins);
        // 二次选择服务
        int sSize = resourceSize > num ? num - winSize : resourceSize - winSize;
        Collection<ResourceModel> sWins = selectRandom(this.connectionPool,map,sids,intValues,groupName,path,sSize);
        wins.addAll(sWins);
        winSize = wins.size();
        // 若依旧不满足则发送邮件
        sendSelectEmail(wins,path,num);
        return wins;

    }

    /**
     * 发送选择邮件
     * @param resourceModels
     * @param sn
     * @param num
     */
    public void sendSelectEmail(Collection<ResourceModel> resourceModels,String sn,int num){
        StringBuilder messageBuilder = new StringBuilder();
        messageBuilder.append("sr:[")
                .append(sn).
                append("] 写入可供选择的服务少于需要的!! 可用服务 ")
                .append(resourceModels.size()).append(", 需要 ")
                .append(num).append("(文件分布见上下文表格)");
        Map<String,String> map = new HashMap<>();
        String part;
        String key;
        for(ResourceModel resource : resourceModels){
            key = resource.getServerId()+"("+resource.getHost()+")";
            part = resource.getMountedPoint(sn);
            map.put(key,part);
        }
        MailWorker.Builder builder = MailWorker.newBuilder(ProgramInfo.getInstance())
                .setModel(this.getClass().getName()+"服务选择")
                .setMessage(messageBuilder.toString())
                .setVariable(map);
        EmailPool.getInstance().sendEmail(builder,false);
    }
    /**
     * 随机选择
     * @param pool
     * @param map
     * @param sids
     * @param intValues
     * @param groupName
     * @param sn
     * @param num
     * @return
     */
    public Collection<ResourceModel> selectRandom(DiskNodeConnectionPool pool, Map<String,ResourceModel> map,Set<String> sids,List<Pair<String,Integer>> intValues,String groupName,String sn,int num){
        List<ResourceModel> resourceModels = new ArrayList<>();
        String key;
        String ip;
        ResourceModel tmp;
        DiskNodeConnection conn;
        //ip选中优先选择
        int tSize = map.size();
        // 按资源选择
        Random random = new Random();
        while(resourceModels.size() != num && resourceModels.size() !=tSize && sids.size() !=tSize){
            key = WeightRandomPattern.getWeightRandom(intValues,random,sids);
            tmp = map.get(key);
            ip = tmp.getHost();
            if(pool != null){
                conn = pool.getConnection(groupName,key);
                if(conn == null || !conn.isValid()){
                    LOG.warn("{} :[{}({})]is unused !!",groupName,key,ip);
                    sids.add(key);
                    continue;
                }
            }
            MailWorker.Builder builder = MailWorker.newBuilder(ProgramInfo.getInstance())
                    .setModel(this.getClass().getName()+"服务选择")
                    .setMessage("sr ["+sn+"]即将 在 "+key+"("+ip+") 服务 写入重复数据");
            EmailPool.getInstance().sendEmail(builder,false);
            sids.add(tmp.getServerId());
        }
        return resourceModels;
    }
    /**
     * 获取已选择服务的services
     * @param wins
     * @return
     */
    public Set<String> selectWins(List<ResourceModel> wins){
        Set<String> set = new HashSet<>();
        for(ResourceModel resourceModel : wins){
            set.add(resourceModel.getServerId());
        }
        return set;
    }
    public List<Pair<String,Integer>> covertValues(Collection<ResourceModel> resources, String path, int centSize){
        List<Pair<String,Double>> values = new ArrayList<>();
        Pair<String,Double> tmpResource;
        double sum;
        String server;
        for(ResourceModel resource : resources){
            server = resource.getServerId();
            // 参数调整，disk写入io大的权重低
            sum = resource.getDiskRemainValue(path) + 1 - resource.getDiskWriteValue(path);
            tmpResource = new Pair<>(server,sum);
            values.add(tmpResource);
        }
        return converDoublesToIntegers(values,centSize);
    }
    /**
     * 服务选择
     * @param intValues
     * @param num
     * @return
     */
    public List<ResourceModel> selectNode(DiskNodeConnectionPool pool,Map<String,ResourceModel> map,List<Pair<String, Integer>>intValues,String groupName,int num){

        List<ResourceModel> resourceModels = new ArrayList<>();
        String key;
        String ip;
        ResourceModel tmp;
        DiskNodeConnection conn;
        //ip选中优先选择
        Set<String> ips = new HashSet<>();
        List<String> uneedServices = new ArrayList<>();
        int tSize = map.size();
        // 按资源选择
        while(resourceModels.size() != num && resourceModels.size() !=tSize && uneedServices.size() !=tSize){
            key = WeightRandomPattern.getWeightRandom(intValues,new Random(),uneedServices);
            tmp = map.get(key);
            ip = tmp.getHost();
            if(pool != null){
                conn = pool.getConnection(groupName,key);
                if(conn == null || !conn.isValid()){
                    LOG.warn("{} :[{}({})]is unused !!",groupName,key,ip);
                    uneedServices.add(key);
                    continue;
                }
            }
            // 不同ip的添加
            if(ips.add(ip)){
                resourceModels.add(tmp);
            }else{
                LOG.info("{} is selectd !! get next", ip);
            }
            uneedServices.add(tmp.getServerId());
        }
        return resourceModels;
    }

    /**
     * 转换为map
     * @param resources
     * @return
     */
    public Map<String,ResourceModel> convertResourceMap(Collection<ResourceModel> resources){
        Map<String,ResourceModel> map = new HashMap<>();
        for(ResourceModel resource : resources){
            map.put(resource.getServerId(),resource);
        }
        return map;
    }

    /**
     * 概述：计算资源比值
     * @param servers
     * @return
     * @user <a href=mailto:zhucg@bonree.com>朱成岗</a>
     */
    private List<Pair<String, Integer>> converDoublesToIntegers(final List<Pair<String, Double>> servers, int preCentSize){
        List<Pair<String,Integer>> dents = new ArrayList<Pair<String,Integer>>();
        int value;
        double sum = 0;
        int centSize = preCentSize<=0 ? 100 : preCentSize;
        for(Pair<String,Double> pair: servers) {
            sum +=pair.getSecond();
        }
        Pair<String,Integer> tmp;
        for(Pair<String,Double> ele : servers){
            tmp = new Pair<>();
            tmp.setFirst(ele.getFirst());
            value = (int)(ele.getSecond()/sum* centSize);
            if(value == 0){
                value = 1;
            }
            tmp.setSecond(value);
            dents.add(tmp);
        }
        return dents;
    }
    @Override
    public List<Pair<String, Integer>> selectAvailableServers(int scene, String storageName, List<String> exceptionServerList, int centSize) throws Exception{
        return null;
    }

    @Override
    public List<Pair<String, Integer>> selectAvailableServers(int scene, int snId, List<String> exceptionServerList, int centSize) throws Exception{
        return null;
    }

    @Override
    public void setLimitParameter(LimitServerResource limits){
        this.limit = limits;
    }

    @Override
    public void update(ResourceModel resource){

    }

    @Override
    public void add(ResourceModel resources){

    }

    @Override
    public void remove(ResourceModel resource){

    }
}
