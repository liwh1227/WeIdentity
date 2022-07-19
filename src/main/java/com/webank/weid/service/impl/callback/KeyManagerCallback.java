package com.webank.weid.service.impl.callback;

import java.util.ArrayList;


import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.webank.weid.service.BaseService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webank.weid.constant.DataDriverConstant;
import com.webank.weid.constant.ErrorCode;
import com.webank.weid.constant.ParamKeyConstant;
import com.webank.weid.exception.DataTypeCastException;
import com.webank.weid.protocol.amop.GetEncryptKeyArgs;
import com.webank.weid.protocol.base.WeIdDocument;
import com.webank.weid.protocol.response.GetEncryptKeyResponse;
import com.webank.weid.protocol.response.ResponseData;
import com.webank.weid.rpc.WeIdService;
import com.webank.weid.rpc.callback.AmopCallback;
import com.webank.weid.service.impl.WeIdServiceImpl;
import com.webank.weid.suite.api.persistence.PersistenceFactory;
import com.webank.weid.suite.api.persistence.inf.Persistence;
import com.webank.weid.suite.api.persistence.params.PersistenceType;
import com.webank.weid.util.DataToolUtils;
import com.webank.weid.util.PropertyUtils;

public class KeyManagerCallback extends AmopCallback {

    private static final Logger logger =  LoggerFactory.getLogger(KeyManagerCallback.class);

    private Persistence dataDriver;

    private PersistenceType persistenceType;

    private WeIdService weidService;

    private WeIdService getWeIdService() {
        if (weidService == null) {
            weidService = new WeIdServiceImpl();
        }
        return weidService;
    }

    private Persistence getDataDriver() {
        String type = PropertyUtils.getProperty("persistence_type");
        if (type.equals("mysql")) {
            persistenceType = PersistenceType.Mysql;
        } else if (type.equals("redis")) {
            persistenceType = PersistenceType.Redis;
        }
        if (dataDriver == null) {
            dataDriver = PersistenceFactory.build(persistenceType);
        }
        return dataDriver;
    }

    @Override
    public GetEncryptKeyResponse onPush(GetEncryptKeyArgs arg) {
        logger.info("[KeyManagerCallback.onPush] begin query key param:{}", arg);
        GetEncryptKeyResponse encryptResponse = new GetEncryptKeyResponse();
        ResponseData<String>  keyResponse = this.getDataDriver().get(
                DataDriverConstant.DOMAIN_ENCRYPTKEY, arg.getKeyId());
        if (keyResponse.getErrorCode().intValue() == ErrorCode.SUCCESS.getCode()
                && StringUtils.isBlank(keyResponse.getResult())) {
            logger.info("[KeyManagerCallback.onPush] the encrypt key is not exists.");
            encryptResponse.setEncryptKey(StringUtils.EMPTY);
            encryptResponse.setErrorCode(ErrorCode.ENCRYPT_KEY_NOT_EXISTS.getCode());
            encryptResponse.setErrorMessage(ErrorCode.ENCRYPT_KEY_NOT_EXISTS.getCodeDesc());
        } else {
            encryptResponse.setEncryptKey(StringUtils.EMPTY);
            if (keyResponse.getErrorCode().intValue() != ErrorCode.SUCCESS.getCode()) {
                encryptResponse.setErrorCode(keyResponse.getErrorCode().intValue());
                encryptResponse.setErrorMessage(keyResponse.getErrorMessage());
                return encryptResponse;
            }
            try {
                Map<String, Object> keyMap = DataToolUtils.deserialize(
                        keyResponse.getResult(),
                        new HashMap<String, Object>().getClass()
                );
                if (!checkAuthority(arg, keyMap)) { // 检查是否有权限
                    encryptResponse.setErrorCode(ErrorCode.ENCRYPT_KEY_NO_PERMISSION.getCode());
                    encryptResponse.setErrorMessage(
                            ErrorCode.ENCRYPT_KEY_NO_PERMISSION.getCodeDesc());
                } else {
                    encryptResponse.setEncryptKey((String)keyMap.get(ParamKeyConstant.KEY_DATA));
                    encryptResponse.setErrorCode(ErrorCode.SUCCESS.getCode());
                    encryptResponse.setErrorMessage(ErrorCode.SUCCESS.getCodeDesc());
                }
            } catch (DataTypeCastException e) {
                logger.error("[KeyManagerCallback.onPush]  deserialize the data error.", e);
                encryptResponse.setErrorCode(ErrorCode.ENCRYPT_KEY_INVALID.getCode());
                encryptResponse.setErrorMessage(ErrorCode.ENCRYPT_KEY_INVALID.getCodeDesc());
            }
        }
        return encryptResponse;
    }

    /**
     * 检查是否有权限获取秘钥数据.
     * @param arg 请求秘钥对应的参数
     * @param keyMap 查询出来的key数据
     * @return
     */
    private boolean checkAuthority(GetEncryptKeyArgs arg, Map<String, Object> keyMap) {
        if (keyMap == null) {
            logger.info("[checkAuthority] illegal input.");
            return false;
        }
        List<String> verifiers = (ArrayList<String>)keyMap.get(ParamKeyConstant.KEY_VERIFIERS);
        // 如果verifiers为empty,或者传入的weId为空，或者weId不在指定列表中，则无权限获取秘钥数据
        if (CollectionUtils.isEmpty(verifiers)
                || StringUtils.isBlank(arg.getWeId())
                || !verifiers.contains(arg.getWeId())) {
            logger.info(
                    "[checkAuthority] no access to get the data, this weid is {}.",
                    arg.getWeId()
            );
            return false;
        }
        // 验证signValue
        ResponseData<WeIdDocument> domRes = this.getWeIdService().getWeIdDocument(arg.getWeId());
        if (domRes.getErrorCode().intValue() != ErrorCode.SUCCESS.getCode()) {
            logger.info(
                    "[checkAuthority] can not get the WeIdDocument, this weid is {}.",
                    arg.getWeId()
            );
            return false;
        }
        ErrorCode errorCode = DataToolUtils.verifySignatureFromWeId(
                arg.getKeyId(),
                arg.getSignValue(),
                domRes.getResult(),
                null
        );
        if (errorCode.getCode() != ErrorCode.SUCCESS.getCode()) {
            logger.info(
                    "[checkAuthority] the data is be changed, this weid is {}.",
                    arg.getWeId()
            );
            return false;
        }
        return true;
    }
}
