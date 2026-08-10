package fi.mstahv.sensorhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.vaadin.flow.component.page.AppShellConfigurator;

@SpringBootApplication
public class SensorHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(SensorHubApplication.class, args);
    }
}
