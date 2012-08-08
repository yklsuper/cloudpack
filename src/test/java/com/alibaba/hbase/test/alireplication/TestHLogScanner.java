package com.alibaba.hbase.test.alireplication;

import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import junit.framework.Assert;

import org.apache.hadoop.fs.FileSystem;
import org.apache.zookeeper.KeeperException;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sourceopen.analyze.hadoop.hbase.TestHBase;
import org.sourceopen.hadoop.hbase.replication.core.HBaseService;
import org.sourceopen.hadoop.hbase.replication.core.hlog.domain.HLogGroup;
import org.sourceopen.hadoop.hbase.replication.producer.HLogScanner;
import org.sourceopen.hadoop.hbase.replication.producer.HLogScanner.GEMap;
import org.sourceopen.hadoop.hbase.replication.producer.ZkHLogPersistence;
import org.sourceopen.hadoop.hbase.utils.HRepConfigUtil;
import org.sourceopen.hadoop.zookeeper.connect.AdvZooKeeper;
import org.sourceopen.hadoop.zookeeper.connect.NothingZookeeperWatch;
import org.sourceopen.hadoop.zookeeper.core.ZNode;

/**
 * 类TestHLogScanner.java的实现描述：TODO 类实现描述
 * 
 * @author zalot.zhaoh Mar 22, 2012 1:50:42 PM
 */
public class TestHLogScanner extends TestHBase {

    static HBaseService hb;

    @BeforeClass
    public static void init() throws Exception {
        startHBaseClusterA(3, 3);
        createDefTable(_confA);
        hb = new HBaseService(_confA);
    }

    @Test
    public void testSThreadHLogScanner() throws Exception {
        AdvZooKeeper zk1 = HRepConfigUtil.createAdvZooKeeperByHBaseConfig(_confA, new NothingZookeeperWatch());
        ZkHLogPersistence dao1 = new ZkHLogPersistence(_confA, zk1);

        FileSystem fs = _utilA.getTestFileSystem();
        ZNode root = HRepConfigUtil.getRootZNode(_confA);
        long sleepTime = 3000;
        long lockTime = 500;
        HLogScanner scan = HLogScanner.newInstance(zk1, root, dao1, hb, lockTime, sleepTime);
        scan.start();
        int count = 0;
        Thread.sleep(lockTime * 2);
        while (true) {
            insertRndData(_poolA, "testA", "colA", "test", rnd.nextInt(1000));
            Thread.sleep(sleepTime * 2);
            GEMap groups = new GEMap();
            groups.put(hb.getAllHLogs());
            groups.put(hb.getAllOldHLogs());
            Assert.assertTrue(dao1.listGroupName().size() == groups.getGroups().size());
            for (HLogGroup group : groups.getGroups()) {
                Assert.assertTrue(dao1.listEntry(group.getGroupName()).size() == group.getEntrys().size());
            }
            System.out.println("checkok -- " + count + " groups [ " + groups.getGroups().size() + " ] ");
            if (count > 10) return;
            count++;
        }
    }

    @Test
    public void testMThreadHLogScanner() throws Exception {
        ZNode root = HRepConfigUtil.getRootZNode(_confA);
        long sleepTime = 3000;
        final long tryLockTime = 500;

        final AdvZooKeeper zk1 = HRepConfigUtil.createAdvZooKeeperByHBaseConfig(_confA, new NothingZookeeperWatch());
        final ZkHLogPersistence dao1 = new ZkHLogPersistence(_confA, zk1);
        final HLogScanner scan1 = HLogScanner.newInstance(zk1, root, dao1, hb, tryLockTime, sleepTime);

        final AdvZooKeeper zk2 = HRepConfigUtil.createAdvZooKeeperByHBaseConfig(_confA, new NothingZookeeperWatch());
        final ZkHLogPersistence dao2 = new ZkHLogPersistence(_confA, zk2);
        final HLogScanner scan2 = HLogScanner.newInstance(zk2, root, dao2, hb, tryLockTime, sleepTime);

        final AdvZooKeeper zk3 = HRepConfigUtil.createAdvZooKeeperByHBaseConfig(_confA, new NothingZookeeperWatch());
        final ZkHLogPersistence dao3 = new ZkHLogPersistence(_confA, zk3);

        int count = 0;

        scan1.start();
        scan2.start();
        Thread.sleep(tryLockTime * 2);

        final AtomicBoolean ab = new AtomicBoolean(true);
        final Thread rndShutDownScan = new Thread() {

            @Override
            public void run() {
                super.run();
                while (true) {
                    if (ab.get()) {
                        try {
                            Thread.sleep(5000);
                            HLogScanner scan = rndShutDownScan(scan1, scan2);
                            if (!scan.isAlive()) {
                                System.out.println("start scan [" + scan.getName() + "] ...");
                                scan.start();
                            }
                        } catch (Exception e) {
                            // e.printStackTrace();
                        }
                        ab.set(!ab.get());
                    }
                }
            }
        };

        // rndShutDownScan.start();

        final Thread rndCloseZK = new Thread() {

            @Override
            public void run() {
                super.run();
                while (true) {
                    if (!ab.get()) {
                        try {
                            Thread.sleep(5000);
                            ZkHLogPersistence dao = rndShutDownDaoZk(dao1, dao2);
                            AdvZooKeeper zk = HRepConfigUtil.createAdvZooKeeperByHBaseConfig(_confA,
                                                                                             new NothingZookeeperWatch());
                            dao.setZoo(zk);
                            System.out.println("start [dao-zk] " + dao.getName());
                        } catch (Exception e) {
                            // e.printStackTrace();
                        }
                        ab.set(!ab.get());
                    }
                }
            }
        };
        // rndCloseZK.start();

        Thread.currentThread().setUncaughtExceptionHandler(new UncaughtExceptionHandler() {

            @Override
            public void uncaughtException(Thread t, Throwable e) {
                rndShutDownScan.stop();
                rndCloseZK.stop();
            }
        });

        Thread.sleep(tryLockTime * 2);
        while (true) {
            insertRndData(_poolA, "testA", "colA", "test", rnd.nextInt(1000));
            Thread.sleep(sleepTime * 10);
            GEMap groups = new GEMap();
            groups.put(hb.getAllHLogs());
            groups.put(hb.getAllOldHLogs());
            Assert.assertTrue(dao3.listGroupName().size() == groups.getGroups().size());
            for (HLogGroup group : groups.getGroups()) {
                Assert.assertTrue(dao3.listEntry(group.getGroupName()).size() == group.getEntrys().size());
            }
            System.out.println("checkok -- " + count + " groups [ " + groups.getGroups().size() + " ] ");
            if (count > 10) return;
            count++;
        }
    }

    static Random rnd = new Random();

    public static HLogScanner rndShutDownScan(HLogScanner scan1, HLogScanner scan2) throws IOException,
                                                                                   KeeperException,
                                                                                   InterruptedException {
        HLogScanner canStopScan;
        if (rnd.nextBoolean()) {
            canStopScan = scan1.isAlive() ? scan1 : scan2;
        } else {
            canStopScan = scan2.isAlive() ? scan2 : scan1;
        }

        if (canStopScan.isAlive()) {
            try {
                canStopScan.stop();
                System.out.println("stop scan [" + canStopScan.getName() + "] ...");
            } catch (Throwable e) {
            }
        }
        return canStopScan;
    }

    private static ZkHLogPersistence rndShutDownDaoZk(ZkHLogPersistence dao1, ZkHLogPersistence dao2)
                                                                                                     throws InterruptedException,
                                                                                                     IOException {
        ZkHLogPersistence canStop;
        if (rnd.nextBoolean()) {
            canStop = dao1;
        } else {
            canStop = dao2;
        }
        canStop.getZoo().close();
        System.out.println("stop [dao] " + canStop.getName());
        return canStop;
    }
}
