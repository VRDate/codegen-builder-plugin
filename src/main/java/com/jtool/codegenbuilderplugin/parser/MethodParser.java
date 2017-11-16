package com.jtool.codegenbuilderplugin.parser;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.jtool.codegenannotation.*;
import com.jtool.codegenbuilderplugin.BuilderMojo;
import com.jtool.codegenbuilderplugin.model.*;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

import static com.jtool.codegenbuilderplugin.util.AnnotationHelper.constrainAnnotationToStr;
import static com.jtool.codegenbuilderplugin.util.AnnotationHelper.hasAnnotation;
import static com.jtool.codegenbuilderplugin.util.CurlExampleHelper.parseCurlExample;

public class MethodParser {

    public static List<CodeGenModel> parseMethodToCodeGenModel(BuilderMojo builderMojo, List<Method> methodLists) throws ClassNotFoundException, IOException, InstantiationException, IllegalAccessException {

        List<CodeGenModel> result = new ArrayList<>();

        for (Method method : methodLists) {
            result.add(parse(builderMojo, method));
        }

        Collections.sort(result);

        return result;
    }

    private static CodeGenModel parse(BuilderMojo builderMojo, Method method) throws ClassNotFoundException, InstantiationException, IllegalAccessException, IOException {

        if (!hasAnnotation(method, CodeGenApi.class)) {
            throw new RuntimeException("本方法应该有@CodeGenApi注解才对");
        }

        final CodeGenModel codeGenModel = new CodeGenModel();

        //分析排序的序号
        codeGenModel.setDocSeq(method.getAnnotation(CodeGenApi.class).docSeq());

        //分析api的名字
        codeGenModel.setApiName(method.getAnnotation(CodeGenApi.class).name());

        //分析说明
        codeGenModel.setDescription(method.getAnnotation(CodeGenApi.class).description());

        //分析http的方法:GET或者POST
        codeGenModel.setHttpMethod(paresHttpMethod(method));

        //分析备注内容
        codeGenModel.setRemark(method.getAnnotation(CodeGenApi.class).remark());

        //分析API的路径值
        codeGenModel.setUrl(paresUrl(method));

        //分析request的class的参数列表
        codeGenModel.setRequestClass(parseRequestClass(method));

        //分析response的class的参数列表
        codeGenModel.setResponseClass(parseResponseClass(method));

        //分析是否rest
        codeGenModel.setRest(parseIsRest(method));

        //分析是否弃用的方法
        codeGenModel.setIsDeprecated(hasAnnotation(method, Deprecated.class));

        //分析接口是给谁用的
        codeGenModel.setForWho(method.getAnnotation(CodeGenApi.class).forWho());

        //分析是否生成SDK
        codeGenModel.setIsGenSDK(method.getAnnotation(CodeGenApi.class).genSDK());

        //分析这个方法的名称
        codeGenModel.setApiMethodName(method.getName());

        //分析成功请求返回的字符串
        codeGenModel.setSuccessReturnJsonStr(parseSuccessReturnString(builderMojo, codeGenModel));

        //分析request的pojo的参数列表 和 分析path param的参数列表
        codeGenModel.setRequestParamModelList(parseRequestParam(builderMojo, method));

        //分析response的pojo的参数列表
        codeGenModel.setResponseParamModelList(parseResponseParam(builderMojo, method));

        //分析获得curlExample
        codeGenModel.setCurlExample(parseCurlExample(builderMojo, codeGenModel));

        return codeGenModel;
    }

    private static Optional<Class> parseRequestClass(Method method) {
        CodeGenRequest codeGenRequest = method.getAnnotation(CodeGenRequest.class);
        if (codeGenRequest != null) {
            return Optional.of(codeGenRequest.value());
        } else {
            return Optional.empty();
        }
    }

    private static Optional<Class> parseResponseClass(Method method) {
        CodeGenResponse codeGenResponse = method.getAnnotation(CodeGenResponse.class);
        if (codeGenResponse != null) {
            return Optional.of(codeGenResponse.value());
        } else {
            return Optional.empty();
        }
    }

    private static boolean parseIsRest(Method method) {
        CodeGenRequest codeGenRequest = method.getAnnotation(CodeGenRequest.class);
        if (codeGenRequest != null && codeGenRequest.isRest()) {
            return true;
        } else {
            return false;
        }
    }

    public static boolean isPostFile(List<ParamModel> requestParamModelList) {
        for(ParamModel requestParamModel : requestParamModelList) {
            if("org.springframework.web.multipart.MultipartFile".equals(requestParamModel.getType())) {
                return true;
            }
        }

        return false;
    }

    private static String parseSuccessReturnString(BuilderMojo builderMojo, CodeGenModel codeGenModel) {

        if(codeGenModel.getResponseClass().isPresent()) {
            try {
                return JSON.toJSONString(genParamJsonObj(builderMojo, codeGenModel.getResponseClass().get()), SerializerFeature.PrettyFormat);
            } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | IOException e) {
                e.printStackTrace();
            }
        }

        return "";
    }

    //生成请求成功返回的json表示
    public static Object genParamJsonObj(BuilderMojo builderMojo, Class clazz) throws ClassNotFoundException, IllegalArgumentException,
            IllegalAccessException, InstantiationException, IOException {

        Object obj = clazz.newInstance();

        for (Field field : FieldUtils.getAllFieldsList(clazz)) {

            field.setAccessible(true);

            if (field.getType().equals(String.class)) {
                field.set(obj, field.getName());
            } else if(field.getType().equals(Boolean.class)){
                field.set(obj, false);
            } else if(field.getType().equals(Short.class) || field.getType().equals(Integer.class)) {
                field.set(obj, 0);
            } else if(field.getType().equals(Long.class)) {
                field.set(obj, 0L);
            } else if(field.getType().equals(Float.class)){
                field.set(obj, 0.0f);
            }  else if (field.getType().equals(Double.class)){
                field.set(obj, 0.0d);
            } else if(field.getType().equals(Date.class)) {
                field.set(obj, new Date());
            } else if (field.getType().equals(List.class) || field.getType().equals(Set.class)) {

                //生成一个list
                Collection<Object> collection;

                if(field.getType().equals(List.class)) {
                    collection = new ArrayList<>();
                } else {
                    collection = new HashSet<>();
                }

                Type fc = field.getGenericType();

                if (fc == null) {
                    continue;
                }

                if (fc instanceof ParameterizedType) {
                    ParameterizedType pt = (ParameterizedType) fc;
                    String className = pt.getActualTypeArguments()[0].getTypeName();

                    //List里面的泛型参数
                    switch (className) {
                        case "java.lang.String" :
                            collection.add("string");
                            break;
                        case "java.lang.Boolean" :
                            collection.add(false);
                            break;
                        case "java.lang.Short" :
                        case "java.lang.Integer" :
                        case "java.lang.Long" :
                            collection.add(0);
                            break;
                        case "java.lang.Float" :
                        case "java.lang.Double" :
                            collection.add(0);
                            break;
                        default:
                            collection.add(genParamJsonObj(builderMojo, (builderMojo.getClassLoaderInterface().loadClass(className))));
                    }
                }

                //设置值到list
                field.set(obj, collection);

            } else if(!field.getType().isPrimitive()) {
                field.set(obj, genParamJsonObj(builderMojo, field.getType()));
            }
        }

        return obj;
    }


    private static List<ParamModel> parseRequestParam(BuilderMojo builderMojo, Method method) {

        List<ParamModel> requestParamModelList = new ArrayList<>();

        CodeGenRequest codeGenRequest = method.getAnnotation(CodeGenRequest.class);
        if (codeGenRequest != null) {
            requestParamModelList.addAll(parseParamModel(builderMojo, codeGenRequest.value()));
        }

        Collections.sort(requestParamModelList);
        return requestParamModelList;
    }

    private static List<ParamModel> parseResponseParam(BuilderMojo builderMojo, Method method) {

        List<ParamModel> responseParamModelList = new ArrayList<>();

        CodeGenResponse codeGenResponse = method.getAnnotation(CodeGenResponse.class);
        if (codeGenResponse != null) {
            responseParamModelList.addAll(parseParamModel(builderMojo, codeGenResponse.value()));
        }

        Collections.sort(responseParamModelList);
        return responseParamModelList;
    }

    private static List<ParamModel> parseParamModel(BuilderMojo builderMojo, Class<?> clazz) {

        List<ParamModel> result = new ArrayList<>();

        for(Field field : FieldUtils.getAllFieldsList(clazz)) {

            //遍历返回pojo的带有@CodeGenField注解的变量
            CodeGenField codeGenField = field.getAnnotation(CodeGenField.class);

            if(codeGenField != null) {
                ParamModel paramModel = new ParamModel();
                paramModel.setKey(field.getName());
                paramModel.setRequired(paresFieldIsRequired(field));
                paramModel.setComment(codeGenField.value());
                paramModel.setType(field.getGenericType().getTypeName());
                paramModel.setConstraintStr(constrainAnnotationToStr(field.getAnnotations()));

                result.add(paramModel);

                if(!field.getType().isPrimitive()) {
                    if (field.getType().equals(List.class)) {//如果是List集合,就得分析List泛型的类型

                        Type type = field.getGenericType();

                        if (type == null) {
                            continue;
                        }

                        if (type instanceof ParameterizedType) {
                            ParameterizedType pt = (ParameterizedType) type;

                            // List集合中的参数类型的字节码
                            try {
                                String genericsTypeStr = pt.getActualTypeArguments()[0].getTypeName();
                                List<ParamModel> paramModelList = parseParamModel(builderMojo, builderMojo.getClassLoaderInterface().loadClass(genericsTypeStr));
                                Collections.sort(paramModelList);
                                paramModel.setSubParamModel(paramModelList);
                            } catch (ClassNotFoundException e) {
                                throw new RuntimeException("找不到类：" + pt.getActualTypeArguments()[0].getTypeName());
                            }
                        }
                    } else if(field.getType().equals(Map.class)) {
                        throw new RuntimeException("不应该使用Map, 请使用pojo");
                    } else {
                        // 如果是普通的类
                        List<ParamModel> paramModelList = parseParamModel(builderMojo, field.getType());
                        Collections.sort(paramModelList);
                        paramModel.setSubParamModel(paramModelList);
                    }
                } else {
                    throw new RuntimeException("不应该使用简单类型");
                }
            }
        }

        return result;
    }

    private static String paresHttpMethod(Method method) {
        String result = "";
        for (RequestMethod requestMethod : method.getAnnotation(RequestMapping.class).method()) {
            if (result.equals("")) {
                result += requestMethod.name();
            } else {
                result += ", " + requestMethod.name();
            }
        }
        if("".equals(result)) {
            result = "GET, POST";
        }
        return result;
    }

    private static String paresUrl(Method method) {
        String result = "";
        for (String url : method.getAnnotation(RequestMapping.class).value()) {
            if (result.equals("")) {
                result += url;
            } else {
                result += ", " + url;
            }
        }
        return result;
    }

    private static boolean paresFieldIsRequired(Field field) {
        return field.getAnnotation(NotNull.class) != null;
    }

}
