package com.ridehub.route.config;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import org.hibernate.cache.jcache.ConfigSettings;
import org.redisson.Redisson;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.redisson.jcache.configuration.RedissonConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.cache.JCacheManagerCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.*;

import tech.jhipster.config.JHipsterProperties;
import tech.jhipster.config.cache.PrefixedKeyGenerator;

@Configuration
@EnableCaching
public class CacheConfiguration {

    private GitProperties gitProperties;
    private BuildProperties buildProperties;

    @Bean
    public javax.cache.configuration.Configuration<Object, Object> jcacheConfiguration(JHipsterProperties jHipsterProperties) {
        MutableConfiguration<Object, Object> jcacheConfig = new MutableConfiguration<>();

        URI redisUri = URI.create(jHipsterProperties.getCache().getRedis().getServer()[0]);

        Config config = new Config();
        // Fix Hibernate lazy initialization https://github.com/jhipster/generator-jhipster/issues/22889
        config.setCodec(new org.redisson.codec.SerializationCodec());
        if (jHipsterProperties.getCache().getRedis().isCluster()) {
            ClusterServersConfig clusterServersConfig = config
                .useClusterServers()
                .setMasterConnectionPoolSize(jHipsterProperties.getCache().getRedis().getConnectionPoolSize())
                .setMasterConnectionMinimumIdleSize(jHipsterProperties.getCache().getRedis().getConnectionMinimumIdleSize())
                .setSubscriptionConnectionPoolSize(jHipsterProperties.getCache().getRedis().getSubscriptionConnectionPoolSize())
                .addNodeAddress(jHipsterProperties.getCache().getRedis().getServer());

            if (redisUri.getUserInfo() != null) {
                clusterServersConfig.setPassword(redisUri.getUserInfo().substring(redisUri.getUserInfo().indexOf(':') + 1));
            }
        } else {
            SingleServerConfig singleServerConfig = config
                .useSingleServer()
                .setConnectionPoolSize(jHipsterProperties.getCache().getRedis().getConnectionPoolSize())
                .setConnectionMinimumIdleSize(jHipsterProperties.getCache().getRedis().getConnectionMinimumIdleSize())
                .setSubscriptionConnectionPoolSize(jHipsterProperties.getCache().getRedis().getSubscriptionConnectionPoolSize())
                .setAddress(jHipsterProperties.getCache().getRedis().getServer()[0]);

            if (redisUri.getUserInfo() != null) {
                singleServerConfig.setPassword(redisUri.getUserInfo().substring(redisUri.getUserInfo().indexOf(':') + 1));
            }
        }
        jcacheConfig.setStatisticsEnabled(true);
        jcacheConfig.setExpiryPolicyFactory(
            CreatedExpiryPolicy.factoryOf(new Duration(TimeUnit.SECONDS, jHipsterProperties.getCache().getRedis().getExpiration()))
        );
        return RedissonConfiguration.fromInstance(Redisson.create(config), jcacheConfig);
    }

    @Bean
    public HibernatePropertiesCustomizer hibernatePropertiesCustomizer(javax.cache.CacheManager cm) {
        return hibernateProperties -> hibernateProperties.put(ConfigSettings.CACHE_MANAGER, cm);
    }

    @Bean
    public JCacheManagerCustomizer cacheManagerCustomizer(javax.cache.configuration.Configuration<Object, Object> jcacheConfiguration) {
        return cm -> {
            createCache(cm, com.ridehub.route.domain.Province.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.Province.class.getName() + ".districts", jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.District.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.District.class.getName() + ".wards", jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.Ward.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.Ward.class.getName() + ".addresses", jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.Address.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.Station.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.Route.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.Route.class.getName() + ".schedules", jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.Vehicle.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.SeatMap.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.Floor.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.Seat.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.Schedule.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.Schedule.class.getName() + ".timeSlots", jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.ScheduleTimeSlot.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.ScheduleOccasion.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.ScheduleOccasion.class.getName() + ".schedules", jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.Trip.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.Staff.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.Driver.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.Attendant.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.SeatLock.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.FileRoute.class.getName(), jcacheConfiguration);
            createCache(cm, com.ridehub.route.domain.PricingTemplate.class.getName(), jcacheConfiguration);
            createCache(cm, "distanceCalculations", jcacheConfiguration);
            // jhipster-needle-redis-add-entry
        };
    }

    private void createCache(
        javax.cache.CacheManager cm,
        String cacheName,
        javax.cache.configuration.Configuration<Object, Object> jcacheConfiguration
    ) {
        javax.cache.Cache<Object, Object> cache = cm.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        } else {
            cm.createCache(cacheName, jcacheConfiguration);
        }
    }

    @Autowired(required = false)
    public void setGitProperties(GitProperties gitProperties) {
        this.gitProperties = gitProperties;
    }

    @Autowired(required = false)
    public void setBuildProperties(BuildProperties buildProperties) {
        this.buildProperties = buildProperties;
    }

    @Bean
    public KeyGenerator keyGenerator() {
        return new PrefixedKeyGenerator(this.gitProperties, this.buildProperties);
    }
}
