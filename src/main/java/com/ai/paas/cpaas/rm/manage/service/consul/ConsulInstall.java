package com.ai.paas.cpaas.rm.manage.service.consul;

import java.io.InputStream;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import com.ai.paas.cpaas.rm.util.AnsibleCommand;
import com.ai.paas.cpaas.rm.util.ExceptionCodeConstants;
import com.ai.paas.cpaas.rm.util.OpenPortUtil;
import com.ai.paas.cpaas.rm.util.TaskUtil;
import com.ai.paas.cpaas.rm.vo.MesosInstance;
import com.ai.paas.cpaas.rm.vo.OpenResourceParamVo;
import com.ai.paas.ipaas.PaasException;
import com.esotericsoftware.minlog.Log;

public class ConsulInstall implements Tasklet {

  @Override
  public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext)
      throws Exception {
    OpenResourceParamVo openParam = TaskUtil.createOpenParam(chunkContext);
    Boolean useAgent = openParam.getUseAgent();
    String aid = openParam.getAid();
    String[] files = {"installConsul.yml", "consul.json"};
    for (String file : files) {
      InputStream in = OpenPortUtil.class.getResourceAsStream("/playbook/consul/" + file);
      String content = TaskUtil.getFile(in);
      TaskUtil.uploadFile(file, content, useAgent, aid);
    }


    List<MesosInstance> list = openParam.getMesosMaster();
    MesosInstance master = openParam.getMesosMaster().get(0);
    String password = master.getPasswd();
    String consulPath = TaskUtil.getSystemProperty("consul");
    String configFile = TaskUtil.getSystemProperty("filepath") + "/consul.json";
    StringBuffer consulCluster = new StringBuffer();
    consulCluster.append("lines=[").append("'").append(this.genConsulInfo(0, master.getIp()))
        .append("'");
    for (int i = 1; i < list.size(); i++) {
      consulCluster.append(",");
      MesosInstance instance = list.get(i);
      consulCluster.append("'").append(this.genConsulInfo(i, instance.getIp())).append("'");
    }
    consulCluster.append("]");

    List<String> configvars = new ArrayList<String>();
    configvars.add("ansible_ssh_pass=" + password);
    configvars.add("ansible_become_pass=" + password);
    configvars.add("url=" + consulPath);
    configvars.add("configfile=" + configFile);
    configvars.add(consulCluster.toString());

    AnsibleCommand command =
        new AnsibleCommand(TaskUtil.getSystemProperty("filepath") + "/installConsul.yml", "root",
            configvars);
    Timestamp start = new Timestamp(System.currentTimeMillis());

    String result = new String();
    try {
      result = TaskUtil.executeFile("installConsul", command.toString(), useAgent, aid);
    } catch (Exception e) {
      Log.error(e.toString());
      result = e.toString();
      throw new PaasException(ExceptionCodeConstants.DubboServiceCode.SYSTEM_ERROR_CODE,
          e.toString());
    } finally {
      // insert log and task record
      int taskId =
          TaskUtil.insertResJobDetail(start, openParam.getClusterId(), command.toString(),
              TaskUtil.getTypeId("consulInstall"));
      TaskUtil.insertResTaskLog(openParam.getClusterId(), taskId, result);
    }
    return RepeatStatus.FINISHED;
  }

  public String genConsulInfo(int i, String ip) {
    return "server consul-" + (i + 1) + " " + ip + ":8600 check";
  }
}
