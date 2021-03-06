package com.ai.paas.cpaas.rm.manage.service.zookeeper;

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
import com.ai.paas.cpaas.rm.util.TaskUtil;
import com.ai.paas.cpaas.rm.vo.MesosInstance;
import com.ai.paas.cpaas.rm.vo.MesosSlave;
import com.ai.paas.cpaas.rm.vo.OpenResourceParamVo;
import com.ai.paas.ipaas.PaasException;
import com.esotericsoftware.minlog.Log;

public class ConfigHostsStep implements Tasklet {

  @Override
  public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext)
      throws Exception {
    InputStream in = ConfigHostsStep.class.getResourceAsStream("/playbook/confighosts.yml");
    String content = TaskUtil.getFile(in);
    OpenResourceParamVo openParam = TaskUtil.createOpenParam(chunkContext);
    String aid = openParam.getAid();
    Boolean useAgent = openParam.getUseAgent();
    TaskUtil.uploadFile("confighosts.yml", content, useAgent, aid);

    List<MesosInstance> mesosMaster = openParam.getMesosMaster();
    List<MesosSlave> mesosSlave = openParam.getMesosSlave();
    StringBuffer shellContext = new StringBuffer();
    shellContext.append("lines=[");
    shellContext.append("'").append("127.0.0.1 localhost").append("'");
    for (int i = 0; i < mesosMaster.size(); i++) {
      MesosInstance instance = mesosMaster.get(i);
      String ip = instance.getIp();
      String name =
          (String) chunkContext.getStepContext().getStepExecution().getJobExecution()
              .getExecutionContext().get(ip);
      this.genHostsLine(instance, name, shellContext);
    }
    for (int i = 0; i < mesosSlave.size(); i++) {
      MesosSlave instance = mesosSlave.get(i);
      String ip = instance.getIp();
      String name =
          (String) chunkContext.getStepContext().getStepExecution().getJobExecution()
              .getExecutionContext().get(ip);
      this.genHostsLine(instance, name, shellContext);
    }
    shellContext.append("]");
    String password = mesosMaster.get(0).getPasswd();
    List<String> vars = new ArrayList<String>();
    vars.add("ansible_ssh_pass=" + password);
    vars.add("ansible_become_pass=" + password);
    vars.add(shellContext.toString());
    AnsibleCommand command =
        new AnsibleCommand(TaskUtil.getSystemProperty("filepath") + "/confighosts.yml", "root",
            vars);
    StringBuffer executeFile = TaskUtil.createBashFile();
    executeFile.append(command.toString());

    Timestamp start = new Timestamp(System.currentTimeMillis());

    String result = new String();
    try {
      result = TaskUtil.executeFile("confighosts", executeFile.toString(), useAgent, aid);
    } catch (Exception e) {
      Log.error(e.toString());
      result = e.toString();
      throw new PaasException(ExceptionCodeConstants.DubboServiceCode.SYSTEM_ERROR_CODE,
          e.toString());
    } finally {
      // insert log and task record
      int taskId =
          TaskUtil.insertResJobDetail(start, openParam.getClusterId(), shellContext.toString(), 3);
      TaskUtil.insertResTaskLog(openParam.getClusterId(), taskId, result);
    }

    return RepeatStatus.FINISHED;
  }

  public void genHostsLine(MesosInstance instance, String name, StringBuffer shellContext) {
    String ip = instance.getIp();
    shellContext.append(",").append("'").append(ip + "  " + name).append("'");

  }
}
