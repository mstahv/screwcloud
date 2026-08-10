package fi.mstahv.sensorhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.vaadin.flow.component.page.AppShellConfigurator;

/*
   Scheduling is enabled for one job: noticing that a device has stopped
   reporting. Every other alert is triggered by an arriving packet, and silence
   arrives as nothing at all — so that one needs a clock behind it.
*/
@EnableScheduling
@SpringBootApplication
public class SensorHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(SensorHubApplication.class, args);
    }
}
