package com.bonree.brfs.client.impl;

import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.time.FastDateFormat;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSONArray;
import com.bonree.brfs.client.InputItem;
import com.bonree.brfs.client.StorageNameStick;
import com.bonree.brfs.client.route.DiskServiceSelectorCache;
import com.bonree.brfs.client.route.DuplicaServiceSelector;
import com.bonree.brfs.client.route.ServiceMetaInfo;
import com.bonree.brfs.client.utils.FilePathBuilder;
import com.bonree.brfs.common.ReturnCode;
import com.bonree.brfs.common.exception.BRFSException;
import com.bonree.brfs.common.http.client.HttpClient;
import com.bonree.brfs.common.http.client.HttpResponse;
import com.bonree.brfs.common.http.client.URIBuilder;
import com.bonree.brfs.common.proto.FileDataProtos.Fid;
import com.bonree.brfs.common.proto.FileDataProtos.FileContent;
import com.bonree.brfs.common.service.Service;
import com.bonree.brfs.common.utils.ProtoStuffUtils;
import com.bonree.brfs.common.write.data.DataItem;
import com.bonree.brfs.common.write.data.FidDecoder;
import com.bonree.brfs.common.write.data.FileDecoder;
import com.bonree.brfs.common.write.data.WriteDataMessage;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;

public class DefaultStorageNameStick implements StorageNameStick {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultStorageNameStick.class);

    private static final String URI_DATA_ROOT = "/duplication/";
    private static final String URI_DISK_NODE_ROOT = "/disk";

    private static final String DEFAULT_SCHEME = "http";

    private String storageName;
    private int storageId;

    private DiskServiceSelectorCache selector;
    private DuplicaServiceSelector dupSelector;
    private HttpClient client;

    private String userName;
    private String passwd;

    public DefaultStorageNameStick(String storageName, int storageId, HttpClient client, DiskServiceSelectorCache selector, DuplicaServiceSelector dupSelector, String username, String passwd) {
        this.storageName = storageName;
        this.storageId = storageId;
        this.client = client;
        this.selector = selector;
        this.dupSelector = dupSelector;
        this.userName = username;
        this.passwd = passwd;
    }

    @Override
    public String[] writeData(InputItem[] itemArrays) {
        WriteDataMessage dataMessage = new WriteDataMessage();
        dataMessage.setStorageNameId(storageId);

        DataItem[] dataItems = new DataItem[itemArrays.length];
        for (int i = 0; i < dataItems.length; i++) {
            dataItems[i] = new DataItem();
            dataItems[i].setUserSequence(i);
            dataItems[i].setBytes(itemArrays[i].getBytes());
        }
        dataMessage.setItems(dataItems);

        try {
            List<Service> serviceList = dupSelector.randomServiceList();
            System.out.println("write data get service count " + serviceList.size());
            if (serviceList.isEmpty()) {
                throw new BRFSException("none disknode!!!");
            }

            for (Service service : serviceList) {
                URI uri = new URIBuilder().setScheme(DEFAULT_SCHEME).setHost(service.getHost()).setPort(service.getPort()).setPath(URI_DATA_ROOT).build();

                HttpResponse response = null;
                try {
                    Map<String, String> headers = new HashMap<String, String>();
                    headers.put("username", userName);
                    headers.put("password", passwd);

                    System.out.println("client -> " + uri.toString() + ", user " + userName + ", passwd " + passwd);
                    response = client.executePost(uri, headers, ProtoStuffUtils.serialize(dataMessage));
                    System.out.println("client response " + response.getStatusCode());
                } catch (Exception e) {
                	e.printStackTrace();
                    continue;
                }

                if (response == null) {
                    throw new Exception("can not get response for writeData!");
                }

                if (response.isReponseOK()) {
                    JSONArray array = JSONArray.parseArray(new String(response.getResponseBody()));
                    String[] fids = new String[array.size()];
                    for (int i = 0; i < array.size(); i++) {
                        fids[i] = array.getString(i);
                    }
                    return fids;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    @Override
    public String writeData(InputItem item) {
        String[] fids = writeData(new InputItem[] { item });
        if (fids != null && fids.length > 0) {
            return fids[0];
        }

        return null;
    }

    @Override
    public InputItem readData(String fid) throws Exception {
        Fid fidObj = FidDecoder.build(fid);
        if (fidObj.getStorageNameCode() != storageId) {
            throw new IllegalAccessException("Storage name of fid is not legal!");
        }

        List<String> parts = new ArrayList<String>();
        parts.add(fidObj.getUuid());
        for (int serverId : fidObj.getServerIdList()) {
            parts.add(String.valueOf(serverId));
        }

        Map<String, String> headers = new HashMap<String, String>();
        headers.put("username", userName);
        headers.put("password", passwd);
        try {
        	List<Integer> excludePot = new ArrayList<Integer>();
            // 最大尝试副本数个server
            for (int i = 0; i < parts.size() - 1; i++) {
                ServiceMetaInfo serviceMetaInfo = selector.readerService(Joiner.on('_').join(parts), excludePot);
                Service service = serviceMetaInfo.getFirstServer();
                LOG.info("read service[{}]", service);
                if (service == null) {
                    throw new BRFSException("none disknode!!!");
                }
                URI uri = new URIBuilder().setScheme(DEFAULT_SCHEME).setHost(service.getHost()).setPort(service.getPort()).setPath(URI_DISK_NODE_ROOT + FilePathBuilder.buildPath(fidObj, storageName, serviceMetaInfo.getReplicatPot())).addParameter("offset", String.valueOf(fidObj.getOffset())).addParameter("size", String.valueOf(fidObj.getSize())).build();
                
                try {
					final HttpResponse response = client.executeGet(uri, headers);
					
					if (response != null && response.isReponseOK()) {
	                    return new InputItem() {

	                        @Override
	                        public byte[] getBytes() {
	                            try {
	                                FileContent content = FileDecoder.contents(response.getResponseBody());
	                                return content.getData().toByteArray();
	                            } catch (Exception e) {
	                                e.printStackTrace();
	                            }

	                            return null;
	                        }
	                    };
	                }
				} catch (Exception e) {
					continue;
				}
                
                // 使用选择的server没有读取到数据，需要进行排除
                excludePot.add(serviceMetaInfo.getReplicatPot());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    @Override
    public boolean deleteData(String startTime, String endTime) {
        LOG.info("start time:" + startTime);
        LOG.info("end time:" + endTime);
        try {
            List<Service> serviceList = dupSelector.randomServiceList();
            if (serviceList.isEmpty()) {
                throw new BRFSException("none disknode!!!");
            }

            for (Service service : serviceList) {
                StringBuilder pathBuilder = new StringBuilder();
                pathBuilder.append(URI_DATA_ROOT).append(storageId).append("/").append(startTime).append("_").append(endTime);

                URI uri = new URIBuilder().setScheme(DEFAULT_SCHEME).setHost(service.getHost()).setPort(service.getPort()).setPath(pathBuilder.toString()).build();

                HttpResponse response = null;
                try {
                    Map<String, String> headers = new HashMap<String, String>();
                    headers.put("username", userName);
                    headers.put("password", passwd);

                    response = client.executeDelete(uri, headers);
                } catch (Exception e) {
                    continue;
                }

                if (response == null) {
                    throw new Exception("can not get response for createStorageName!");
                }

                if (response.isReponseOK()) {
                    return true;
                }

                String code = new String(response.getResponseBody());
                ReturnCode returnCode = ReturnCode.checkCode(storageName, code);
                LOG.info("returnCode:" + returnCode);
            }
        } catch (IllegalArgumentException e) {
            LOG.error("time format error!!", e);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

    @Override
    public void close() throws IOException {
    }

    @Override
    public boolean deleteData(long startTime, long endTime) {
        DateTime start = new DateTime(startTime);
        DateTime end = new DateTime(endTime);
        return deleteData(start.toString(), end.toString());
    }

    @Override
    public boolean deleteData(Date startTime, Date endTime) {
        DateTime start = new DateTime(startTime.getTime());
        DateTime end = new DateTime(endTime.getTime());
        return deleteData(start.toString(), end.toString());
    }

    @Override
    public boolean deleteData(String startTime, String endTime, String dateForamt) {
        FastDateFormat fastDateFormat = FastDateFormat.getInstance(dateForamt);
        try {
            Date startDate = fastDateFormat.parse(startTime);
            Date endDate = fastDateFormat.parse(endTime);
            DateTime start = new DateTime(startDate.getTime());
            DateTime end = new DateTime(endDate.getTime());
            return deleteData(start.toString(), end.toString());
        } catch (ParseException e) {
            LOG.error("parse time error!!", e);
            return false;
        }

    }

}
