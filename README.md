# FlinkSQL field lineage solution and source code

English | [简体中文](README_CN.md)

The core idea is to parse SQL through Calcite to generate a RelNode tree of relational expressions. Then get the optimized logical paln through optimization stage, and finally call Calcite RelMetadataQuery to get the lineage relationship at the field level.  
<br/>

| Number | Author | Version | time | Remark |
| -- | --- | --- | --- | --- |
| 1 | HamaWhite | 1.0.0 | 2022-08-09 | 1. Add documentation and source code |
| 2 | HamaWhite | 2.0.0 | 2022-11-24 | 1. Support Watermark</br> 2. Support UDTF </br> 3. Change Calcite source code modification method </br> 4. Upgrade Hudi and Mysql CDC versions |
| 3 | HamaWhite | 2.0.1 | 2022-12-01 | 1. Support field AS LOCALTIMESTAMP |
| 4 | HamaWhite | 2.0.2 | 2022-12-30 | 1. Support CEP</br> 2. Support ROW_NUMBER() |
| 5 | HamaWhite | 3.0.0-SNAPSHOT | 2023-01-03 | 1. Support displaying transform between fields |



</br>
Source Address: https://github.com/HamaWhiteGG/flink-sql-lineage

Author Email: song.bs@dtwave-inc.com

## 一、Basic introduction
### 1.1 Introduction to Apache Calcite
Apache Calcite is an open source dynamic data management framework that provides a standard SQL language, multiple query optimizations, and the ability to connect to various data sources, but does not include data storage, algorithms for processing data, and repositories for storing metadata.
Calcite adopts a general idea of big data query framework in the industry. Its goal is "one size fits all", hoping to provide a unified query engine for different computing platforms and data sources. Calcite is a powerful SQL computing engine, and the SQL engine module inside Flink is also based on Calcite.

The Calcite workflow is shown in the figure below, generally divided into Parser, Validator, Converter, and Optimizer stages.
![1.1 Calcite workflow diagram.png](https://github.com/HamaWhiteGG/flink-sql-lineage/blob/main/data/images/1.1%20Calcite%20workflow%20diagram.png)

For details, please refer to [How to screw SQL to anything with Apache Calcite](https://zephyrnet.com/how-to-screw-sql-to-anything-with-apache-calcite/)

### 1.2 Introduction to Calcite RelNode
In CalciteSQL parsing, the SqlNode syntax tree generated by the Parser will be converted into a relational operator tree (RelNode Tree) in the Converter stage after being verified by the Validator, as shown in the figure below.
![1.2 Calcite SqlNode vs RelNode.png](https://github.com/HamaWhiteGG/flink-sql-lineage/blob/main/data/images/1.2%20Calcite%20SqlNode%20vs%20RelNode.png)


### 1.3 Component version information
| Component name | Version | Remark |
| --- | --- | --- |
| Flink | 1.14.4 |  
| Hadoop | 3.2.2 |  |
| Hive | 3.1.2 |  |
| Hudi-flink1.14-bundle | 0.12.1 |  |
| Flink-connector-mysql-cdc | 2.2.1 |  |
| JDK | 1.8 | |
| Scala | 2.12 | |

## 二、The core idea of field lineage analysis
### 2.1 FlinkSQL Execution Process Analysis
According to the source code, the execution process of FlinkSQL is shown in the figure below, which is mainly divided into five stages:

1. **Parse Stage**

For syntax analysis, use JavaCC to convert SQL into an abstract syntax tree (AST), which is represented by SqlNode in Calcite.

2. **Validate Stage**

Grammatical verification, grammatical verification based on metadata information, such as whether the queried table, field, and function exist, will validate the clauses such as from, where, group by, having, select, orader by, etc. After verification, the SqlNode is still composed The syntax tree AST.

3. **Convert Stage**

Semantic analysis, based on SqlNode and metadata information to build a relational expression RelNode tree, which is the original version of the logical plan.

4. **Optimize Stage**

Logical plan optimization, the optimizer will perform equivalent transformations based on rules, such as predicate pushdown, column pruning, etc., and finally obtain the optimal query plan.

5. **Execute Stage**

Translate the logical query plan into a physical execution plan, generate StreamGraph and JobGraph in turn, and finally submit it for operation.
![2.1 FlinkSQL execution flowchart.png](https://github.com/HamaWhiteGG/flink-sql-lineage/blob/main/data/images/2.1%20FlinkSQL%20execution%20flowchart.png)
                               
> Note 1: The names of Abstract Syntax Tree, Optimized Physical Plan, Optimized Execution Plan, and Physical Execution Plan in the figure come from the explain() method in StreamPlanner.<br/>
> Note 2: Compared with Calcite's official workflow diagram, Validate and Convert are divided into two stages here.

### 2.2 Field lineage analysis ideas
![2.2 FlinkSQL field lineage analysis thought.png](https://github.com/HamaWhiteGG/flink-sql-lineage/blob/main/data/images/2.2%20FlinkSQL%20field%20lineage%20analysis%20thought.png)
                                                         
FlinkSQL field lineage analysis is divided into three stages:

1. Parse, Validate, and Convert the input SQL to generate a relational expression RelNode tree, corresponding to steps 1, 2, and 3 in the FlinkSQL execution flowchart.
2. In the optimization phase, only the Optimized Logical Plan can be generated instead of the original Optimized Physical Plan. To **fix** FlinkSQL execute step 4 in the flowchart.

![2.2 FlinkSQL field lineage analysis flowchart.png](https://github.com/HamaWhiteGG/flink-sql-lineage/blob/main/data/images/2.2%20FlinkSQL%20field%20lineage%20analysis%20flowchart.png)

3. For the logical RelNode generated by optimization in the previous step, call getColumnOrigins(RelNode rel, int column) of RelMetadataQuery to query the original field information. Then construct the blood relationship and return the result.
### 2.3 Explanation of the core source code
The parseFieldLineage(String sql) method is an externally provided field lineage analysis API, which executes three major steps.

```java
public List<FieldLineage> parseFieldLineage(String sql) {
    LOG.info("Input Sql: \n {}", sql);
    // 1. Generate original relNode tree
    Tuple2<String, RelNode> parsed = parseStatement(sql);
    String sinkTable = parsed.getField(0);
    RelNode oriRelNode = parsed.getField(1);

    // 2. Optimize original relNode to generate Optimized Logical Plan
    RelNode optRelNode = optimize(oriRelNode);

    // 3. Build lineage based from RelMetadataQuery
    return buildFiledLineageResult(sinkTable, optRelNode);
}
```
#### 2.3.1 Generate RelNode tree according to SQL
Just call the ParserImpl.List<Operation> parse(String statement) method, and then return the calciteTree in the first operation. This code is restricted to only support the blood relationship of Insert.

```java
private Tuple2<String, RelNode> parseStatement(String sql) {
    List<Operation> operations = tableEnv.getParser().parse(sql);
    
    if (operations.size() != 1) {
        throw new TableException(
            "Unsupported SQL query! only accepts a single SQL statement.");
    }
    Operation operation = operations.get(0);
    if (operation instanceof CatalogSinkModifyOperation) {
        CatalogSinkModifyOperation sinkOperation = (CatalogSinkModifyOperation) operation;
        
        PlannerQueryOperation queryOperation = (PlannerQueryOperation) sinkOperation.getChild();
        RelNode relNode = queryOperation.getCalciteTree();
        return new Tuple2<>(
            sinkOperation.getTableIdentifier().asSummaryString(),
            relNode);
    } else {
        throw new TableException("Only insert is supported now.");
    }
}
```
#### 2.3.2 Generate Optimized Logical Plan
In the logical plan optimization stage of step 4, according to the source code, the core is to call the optimization strategy in FlinkStreamProgram, 
which includes 12 stages (subquery_rewrite, temporal_join_rewrite...logical_rewrite, time_indicator, physical, physical_rewrite), 
and the optimized one is Optimized Physical Plan.According to the principle of SQL field lineage analysis, as long as logical_rewrite is optimized after parsing,
copy the FlinkStreamProgram source code to the FlinkStreamProgramWithoutPhysical class, and delete the time_indicator, physical, physical_rewrite strategies and the last chainedProgram.addLast related code. 
Then call the optimize method core code as follows:
```java

//  this.flinkChainedProgram = FlinkStreamProgramWithoutPhysical.buildProgram(configuration);

/**
 *  Calling each program's optimize method in sequence.
 */
private RelNode optimize(RelNode relNode) {
    return flinkChainedProgram.optimize(relNode, new StreamOptimizeContext() {
        @Override
        public boolean isBatchMode() {
            return false;
        }

        @Override
        public TableConfig getTableConfig() {
            return tableEnv.getConfig();
        }

        @Override
        public FunctionCatalog getFunctionCatalog() {
            return getPlanner().getFlinkContext().getFunctionCatalog();
        }

        @Override
        public CatalogManager getCatalogManager() {
            return tableEnv.getCatalogManager();
        }

        @Override
        public SqlExprToRexConverterFactory getSqlExprToRexConverterFactory() {
            return getPlanner().getFlinkContext().getSqlExprToRexConverterFactory();
        }

        @Override
        public <C> C unwrap(Class<C> clazz) {
            return getPlanner().getFlinkContext().unwrap(clazz);

        }

        @Override
        public FlinkRelBuilder getFlinkRelBuilder() {
            return getPlanner().getRelBuilder();
        }

        @Override
        public boolean needFinalTimeIndicatorConversion() {
            return true;
        }

        @Override
        public boolean isUpdateBeforeRequired() {
            return false;
        }

        @Override
        public MiniBatchInterval getMiniBatchInterval() {
            return MiniBatchInterval.NONE;
        }


        private PlannerBase getPlanner() {
            return (PlannerBase) tableEnv.getPlanner();
        }
    });
}
```
> Note: This code can be written with reference to the optimizeTree method in StreamCommonSubGraphBasedOptimizer.

#### 2.3.3 Query the original field and construct the lineage
Call getColumnOrigins(RelNode rel, int column) of RelMetadataQuery to query the original field information, then construct blood relationship, and return the result.

buildFiledLineageResult(String sinkTable, RelNode optRelNode)
```java
private List<FieldLineage> buildFiledLineageResult(String sinkTable, RelNode optRelNode) {
    // target columns
    List<String> targetColumnList = tableEnv.from(sinkTable)
            .getResolvedSchema()
            .getColumnNames();
    
    // check the size of query and sink fields match
    validateSchema(sinkTable, optRelNode, targetColumnList);

    RelMetadataQuery metadataQuery = optRelNode.getCluster().getMetadataQuery();

    List<FieldLineage> fieldLineageList = new ArrayList<>();

    for (int index = 0; index < targetColumnList.size(); index++) {
        String targetColumn = targetColumnList.get(index);

        LOG.debug("**********************************************************");
        LOG.debug("Target table: {}", sinkTable);
        LOG.debug("Target column: {}", targetColumn);

        Set<RelColumnOrigin> relColumnOriginSet = metadataQuery.getColumnOrigins(optRelNode, index);

        if (CollectionUtils.isNotEmpty(relColumnOriginSet)) {
            for (RelColumnOrigin relColumnOrigin : relColumnOriginSet) {
                // table
                RelOptTable table = relColumnOrigin.getOriginTable();
                String sourceTable = String.join(".", table.getQualifiedName());

                // filed
                int ordinal = relColumnOrigin.getOriginColumnOrdinal();
                List<String> fieldNames = table.getRowType().getFieldNames();
                String sourceColumn = fieldNames.get(ordinal);
                LOG.debug("----------------------------------------------------------");
                LOG.debug("Source table: {}", sourceTable);
                LOG.debug("Source column: {}", sourceColumn);

                // add record
                fieldLineageList.add(buildRecord(sourceTable, sourceColumn, sinkTable, targetColumn));
            }
        }
    }
    return fieldLineageList;
}
```
## 三、Test Results
Detailed test cases can be viewed in the unit test in the code, only some test points are described here.
### 3.1 Create table statement
Create three new tables below, namely: ods_mysql_users, dim_mysql_company and dwd_hudi_users.
#### 3.1.1 New mysql cdc table-ods_mysql_users


```sql
DROP TABLE IF EXISTS ods_mysql_users;

CREATE TABLE ods_mysql_users(
  id BIGINT,
  name STRING,
  birthday TIMESTAMP(3),
  ts TIMESTAMP(3),
  proc_time as proctime()
) WITH (
  'connector' = 'mysql-cdc',
  'hostname' = '192.168.90.xxx',
  'port' = '3306',
  'username' = 'root',
  'password' = 'xxx',
  'server-time-zone' = 'Asia/Shanghai',
  'database-name' = 'demo',
  'table-name' = 'users'
);
```
#### 3.1.2 New mysql dim table-dim_mysql_company

```sql
DROP TABLE IF EXISTS dim_mysql_company;

CREATE TABLE dim_mysql_company (
    user_id BIGINT, 
    company_name STRING
) WITH (
    'connector' = 'jdbc',
    'url' = 'jdbc:mysql://192.168.90.xxx:3306/demo?useSSL=false&characterEncoding=UTF-8',
    'username' = 'root',
    'password' = 'xxx',
    'table-name' = 'company'
);
```
#### 3.1.3 New hudi sink table-dwd_hudi_users

```sql
DROP TABLE IF EXISTS dwd_hudi_users;

CREATE TABLE dwd_hudi_users (
    id BIGINT,
    name STRING,
    company_name STRING,
    birthday TIMESTAMP(3),
    ts TIMESTAMP(3),
    `partition` VARCHAR(20)
) PARTITIONED BY (`partition`) WITH (
    'connector' = 'hudi',
    'table.type' = 'COPY_ON_WRITE',
    'path' = 'hdfs://192.168.90.xxx:9000/hudi/dwd_hudi_users',
    'read.streaming.enabled' = 'true',
    'read.streaming.check-interval' = '1'
);
```
### 3.2 Test SQL and blood relationship results
#### 3.2.1 Test insert-select

- Test SQL

```sql
INSERT INTO
    dwd_hudi_users
SELECT
    id,
    name,
    name as company_name,
    birthday,
    ts,
    DATE_FORMAT(birthday, 'yyyyMMdd')
FROM
    ods_mysql_users
```

- Test Result

| **sourceTable** | **sourceColumn** | **targetTable** | **targetColumn** |
| --- | --- | --- | --- |
| ods_mysql_users | id | dwd_hudi_users | id |
| ods_mysql_users | name | dwd_hudi_users | name |
| ods_mysql_users | name | dwd_hudi_users | company_name |
| ods_mysql_users | birthday | dwd_hudi_users | birthday |
| ods_mysql_users | ts | dwd_hudi_users | ts |
| ods_mysql_users | birthday | dwd_hudi_users | partition |

#### 3.2.2 Test insert-select-join

- Test SQL

```sql
SELECT
    a.id as id1,
    CONCAT(a.name, b.company_name),
    b.company_name,
    a.birthday,
    a.ts,
    DATE_FORMAT(a.birthday, 'yyyyMMdd') as p
FROM
    ods_mysql_users as a
JOIN 
    dim_mysql_company as b
ON a.id = b.user_id
```

- RelNode Tree display

Original RelNode
```shell
 LogicalProject(id1=[$0], EXPR$1=[CONCAT($1, $6)], company_name=[$6], birthday=[$2], ts=[$3], p=[DATE_FORMAT($2, _UTF-16LE'yyyyMMdd')])
  LogicalJoin(condition=[=($0, $5)], joinType=[inner])
    LogicalProject(id=[$0], name=[$1], birthday=[$2], ts=[$3], proc_time=[PROCTIME()])
      LogicalTableScan(table=[[hive, flink_demo, ods_mysql_users]])
    LogicalTableScan(table=[[hive, flink_demo, dim_mysql_company]])
```
The Optimized RelNode results optimized by optimize(RelNode relNode) are as follows:
```shell
 FlinkLogicalCalc(select=[id AS id1, CONCAT(name, company_name) AS EXPR$1, company_name, birthday, ts, DATE_FORMAT(birthday, _UTF-16LE'yyyyMMdd') AS p])
  FlinkLogicalJoin(condition=[=($0, $4)], joinType=[inner])
    FlinkLogicalTableSourceScan(table=[[hive, flink_demo, ods_mysql_users]], fields=[id, name, birthday, ts])
    FlinkLogicalTableSourceScan(table=[[hive, flink_demo, dim_mysql_company]], fields=[user_id, company_name])
```

- Test Result

| **sourceTable** | **sourceColumn** | **targetTable** | **targetColumn** |
| --- | --- | --- | --- |
| ods_mysql_users | id | dwd_hudi_users | id |
| dim_mysql_company | company_name | dwd_hudi_users | name |
| ods_mysql_users | name | dwd_hudi_users | name |
| dim_mysql_company | company_name | dwd_hudi_users | company_name |
| ods_mysql_users | birthday | dwd_hudi_users | birthday |
| ods_mysql_users | ts | dwd_hudi_users | ts |
| ods_mysql_users | birthday | dwd_hudi_users | partition |

#### 3.2.3 Test insert-select-lookup-join

After the above steps are completed, the field lineage analysis of Lookup Join is not yet supported. The test situation is as follows.

- Test SQL

```sql
SELECT
    a.id as id1,
    CONCAT(a.name, b.company_name),
    b.company_name,
    a.birthday,
    a.ts,
    DATE_FORMAT(a.birthday, 'yyyyMMdd') as p
FROM
    ods_mysql_users as a
JOIN 
    dim_mysql_company FOR SYSTEM_TIME AS OF a.proc_time AS b
ON a.id = b.user_id
```

- Test Result

| **sourceTable** | **sourceColumn** | **targetTable** | **targetColumn** |
| --- | --- | --- | --- |
| ods_mysql_users | id | dwd_hudi_users | id |
| ods_mysql_users | name | dwd_hudi_users | name |
| ods_mysql_users | birthday | dwd_hudi_users | birthday |
| ods_mysql_users | ts | dwd_hudi_users | ts |
| ods_mysql_users | birthday | dwd_hudi_users | partition |

It can be seen that the field relationship of the dimension table dim_mysql_company is lost, so proceed to the following steps.

## 四、Modify Calcite source code to support Lookup Join
### 4.1 Implementation ideas
For Lookup Join, Parser will parse the SQL statement 'FOR SYSTEM_TIME AS OF' into SqlSnapshot (SqlNode), and validate() will convert it into LogicalSnapshot (RelNode).
Lookup Join-Original RelNode

```shell
 LogicalProject(id1=[$0], EXPR$1=[CONCAT($1, $6)], company_name=[$6], birthday=[$2], ts=[$3], p=[DATE_FORMAT($2, _UTF-16LE'yyyyMMdd')])
  LogicalCorrelate(correlation=[$cor0], joinType=[inner], requiredColumns=[{0, 4}])
    LogicalProject(id=[$0], name=[$1], birthday=[$2], ts=[$3], proc_time=[PROCTIME()])
      LogicalTableScan(table=[[hive, flink_demo, ods_mysql_users]])
    LogicalFilter(condition=[=($cor0.id, $0)])
      LogicalSnapshot(period=[$cor0.proc_time])
        LogicalTableScan(table=[[hive, flink_demo, dim_mysql_company]])
```

However, the RelMdColumnOrigins Handler class in calcite-core does not handle the RelNode of the Snapshot type, resulting in an empty return, and then the blood relationship of the Lookup Join field is lost. Therefore, it is necessary to add a getColumnOrigins(Snapshot rel,RelMetadataQuery mq, int iOutputColumn) method for handling Snapshots in RelMdColumnOrigins.

Since flink-table-planner is packaged by maven-shade-plugin, after modifying calcite-core, the flink package needs to be repackaged. flink-table/flink-table-planner/pom.xml.

```xml

<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  ...
    <artifactSet>
      <includes combine.children="append">
        <include>org.apache.calcite:*</include>
        <include>org.apache.calcite.avatica:*</include>
  ...             
```

This article gives the basic operation steps in the following sections 4.2-4.4, respectively describing how to modify the source code of calcite and flink, and how to compile and package them.

At the same time, another implementation path is also provided in Section 4.5, that is, to add the getColumnOrigins method by dynamically editing Java bytecode technology. The source code has adopted this technology by default, and readers can also skip directly to Section 4.5 for reading.

### 4.2 Recompile Calcite source code
#### 4.2.1 Download the source code and create a branch
The calcite version that flink1.14.4 depends on is 1.26.0, so modify the source code based on tag calcite-1.26.0. And add a version number after the original 3-digit version number to distinguish it from the officially released version.
```shell
# Download the source code on github
$ git clone git@github.com:apache/calcite.git

# Switch to the calcite-1.26.0 tag
$ git checkout calcite-1.26.0

# New branch calcite-1.26.0.1
$ git checkout -b calcite-1.26.0.1
```
#### 4.2.2 Modify the source code

1. In the calcite-core module, add the method getColumnOrigins(Snapshot rel,RelMetadataQuery mq, int iOutputColumn) to the RelMdColumnOrigins class. org.apache.calcite.rel.metadata.RelMdColumnOrigins
```java
  /**
   * Support the field blood relationship of lookup join
   */
  public Set<RelColumnOrigin> getColumnOrigins(Snapshot rel,
                                               RelMetadataQuery mq, int iOutputColumn) {
      return mq.getColumnOrigins(rel.getInput(), iOutputColumn);
  }
```

2. Modify the version number to 1.26.0.1, calcite/gradle.properties
```properties
# before modify
calcite.version=1.26.0
# after modify
calcite.version=1.26.0.1
```

3. Delete the SNAPSHOT on the packaging name. Since the Gradlew packaging parameters have not been studied, the build.gradle.kts code is directly modified here.

   calcite/build.gradle.kts
```kotlin
# before modify
val buildVersion = "calcite".v + releaseParams.snapshotSuffix

# after modify
val buildVersion = "calcite".v
```
#### 4.2.3 Compile source code and push to local repository
```shell
# Compile the source code
$ ./gradlew build -x test 

# Push to local warehouse
$ ./gradlew publishToMavenLocal
```
After running successfully, check the local maven warehouse, and calcite-core-1.26.0.1.jar has been generated.
```shell
$ ll ~/.m2/repository/org/apache/calcite/calcite-core/1.26.0.1

-rw-r--r--  1 baisong  staff  8893065  8  9 13:51 calcite-core-1.26.0.1-javadoc.jar
-rw-r--r--  1 baisong  staff  3386193  8  9 13:51 calcite-core-1.26.0.1-sources.jar
-rw-r--r--  1 baisong  staff  2824504  8  9 13:51 calcite-core-1.26.0.1-tests.jar
-rw-r--r--  1 baisong  staff  5813238  8  9 13:51 calcite-core-1.26.0.1.jar
-rw-r--r--  1 baisong  staff     5416  8  9 13:51 calcite-core-1.26.0.1.pom
```
### 4.3 Recompile Flink source code
#### 4.2.1 Download the source code and create a branch
Modify the source code based on tag release-1.14.4. And add a version number after the original 3-digit version number to distinguish it from the officially released version.
```shell
# Download the flink source code on github
$ git clone git@github.com:apache/flink.git

# Switch to the release-1.14.4 tag
$ git checkout release-1.14.4

# New branch release-1.14.4.1
$ git checkout -b release-1.14.4.1
```
#### 4.3.2 Modify the source code

1. In the flink-table module, modify the version of calcite.version to 1.26.0.1, and flink-table-planner will refer to this version number. That is, let flink-table-planner refer to calcite-core-1.26.0.1. flink/flink-table/pom.xml.
```xml
<properties>
    <!-- When updating Janino, make sure that Calcite supports it as well. -->
    <janino.version>3.0.11</janino.version>
    <!--<calcite.version>1.26.0</calcite.version>-->
    <calcite.version>1.26.0.1</calcite.version>
    <guava.version>29.0-jre</guava.version>
</properties>
```

2. Modify the flink-table-planner version number to 1.14.4.1, including the following 3 points. flink/flink-table/flink-table-planner/pom.xml.
```xml

<artifactId>flink-table-planner_${scala.binary.version}</artifactId>
<!--1. add this line-->
<version>1.14.4.1</version>
<name>Flink : Table : Planner</name>

<!--2. Globally replace ${project.version} with ${parent.version}-->

<!--3. Add this dependency and force the flink-test-utils-junit version to be specified, otherwise the compilation will report an error-->
<dependency>
    <artifactId>flink-test-utils-junit</artifactId>
    <groupId>org.apache.flink</groupId>
    <version>${parent.version}</version>
    <scope>test</scope>
</dependency>

```
#### 4.3.3 Compile source code and push to remote warehouse

```shell
# compile only flink-table-planner
$ mvn clean install -pl flink-table/flink-table-planner -am -Dscala-2.12 -DskipTests -Dfast -Drat.skip=true -Dcheckstyle.skip=true -Pskip-webui-build
```
After running successfully, check the local maven warehouse, flink-table-planner_2.12-1.14.4.1.jar has been generated
```shell
$ ll ~/.m2/repository/org/apache/flink/flink-table-planner_2.12/1.14.4.1

-rw-r--r--  1 baisong  staff  11514580 11 24 18:27 flink-table-planner_2.12-1.14.4.1-tests.jar
-rw-r--r--  1 baisong  staff  35776592 11 24 18:28 flink-table-planner_2.12-1.14.4.1.jar
-rw-r--r--  1 baisong  staff        40 11 23 17:13 flink-table-planner_2.12-1.14.4.1.jar.sha1
-rw-r--r--  1 baisong  staff     15666 11 24 18:28 flink-table-planner_2.12-1.14.4.1.pom
-rw-r--r--  1 baisong  staff        40 11 23 17:12 flink-table-planner_2.12-1.14.4.1.pom.sha1
```

If you want to push to the Maven warehouse, modify pom.xml to add the warehouse address.


```xml
<distributionManagement>
    <repository>
        <id>releases</id>
        <url>http://xxx.xxx-inc.com/repository/maven-releases</url>
    </repository>
    <snapshotRepository>
        <id>snapshots</id>
        <url>http://xxx.xxx-inc.com/repository/maven-snapshots</url>
    </snapshotRepository>
</distributionManagement>
```
```shell
# Enter the flink-table-planner module
$ cd flink-table/flink-table-planner

# Push to remote warehouse
$ mvn clean deploy -Dscala-2.12 -DskipTests -Dfast -Drat.skip=true -Dcheckstyle.skip=true -Pskip-webui-build -T 1C

```
### 4.4 Modify Flink dependency version and test Lookup Join
Modify the version of flink-table-planner dependent in pom.xml to 1.14.4.1


```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-table-planner_2.12</artifactId>
    <version>1.14.4.1</version>
</dependency>
```

Execute the test case in Section 3.2.3 to get the Lookup Join lineage results as follows, which already includes the field lineage relationship of the dimension table dim_mysql_company.

| **sourceTable** | **sourceColumn** | **targetTable** | **targetColumn** |
| --- | --- | --- | --- |
| ods_mysql_users | id | dwd_hudi_users | id |
| dim_mysql_company | company_name | dwd_hudi_users | name |
| ods_mysql_users | name | dwd_hudi_users | name |
| dim_mysql_company | company_name | dwd_hudi_users | company_name |
| ods_mysql_users | birthday | dwd_hudi_users | birthday |
| ods_mysql_users | ts | dwd_hudi_users | ts |
| ods_mysql_users | birthday | dwd_hudi_users | partition |

### 4.5 Dynamically edit Java bytecode to add getColumnOrigins method
Javassist is a class library that can dynamically edit Java bytecode. It can define a new class and load it into the JVM when the Java program is running, and can also modify a class file when the JVM is loaded.
Therefore, this article uses Javassist technology to dynamically add the getColumnOrigins(Snapshot rel,RelMetadataQuery mq, int iOutputColumn) method to the RelMdColumnOrigins class.

The core code is as follows:
```java
/**
 * Dynamic add getColumnOrigins method to class RelMdColumnOrigins by javassist:
 *
 * public Set<RelColumnOrigin> getColumnOrigins(Snapshot rel,RelMetadataQuery mq, int iOutputColumn) {
 *      return mq.getColumnOrigins(rel.getInput(), iOutputColumn);
 * }
 */
static {
	try {
		ClassPool classPool = ClassPool.getDefault();
		CtClass ctClass = classPool.getCtClass("org.apache.calcite.rel.metadata.RelMdColumnOrigins");

		CtClass[] parameters = new CtClass[]{classPool.get(Snapshot.class.getName())
                , classPool.get(RelMetadataQuery.class.getName())
                , CtClass.intType
		};
		// add method
		CtMethod ctMethod = new CtMethod(classPool.get("java.util.Set"), "getColumnOrigins", parameters, ctClass);
		ctMethod.setModifiers(Modifier.PUBLIC);
		ctMethod.setBody("{return $2.getColumnOrigins($1.getInput(), $3);}");
		ctClass.addMethod(ctMethod);
		// load the class
		ctClass.toClass();
	} catch (Exception e) {
		throw new TableException("Dynamic add getColumnOrigins() method exception.", e);
	}
}
```
> Note 1: You can also copy the RelMdColumnOrigins class and package to the project, and then manually add the getColumnOrigins method. However, this method is not compatible enough. After subsequent iterations of the calcite source code, the bloodline code must be corrected along with calcite.

After the above code is added, after executing the Lookup Join test case, you can see the field blood relationship of the dimension table dim_mysql_company, as shown in the table in Section 4.4.

## 五、Flink's other advanced syntax support
After the release of version 1.0.0, the reader @SinyoWong found out that the field lineage analysis of Table Functions (UDTF) and Watermark syntax was not yet supported, so he began to further improve the code.

See details issue: [https://github.com/HamaWhiteGG/flink-sql-lineage/issues/3](https://github.com/HamaWhiteGG/flink-sql-lineage/issues/3), thanks.

### 5.1 Change the Calcite source code modification method
Since the following steps also need to modify the RelMdColumnOrigins class in the Calcite source code, the two methods of modifying the Calcite source code recompilation and dynamically editing the bytecode introduced in Chapter 4 are too cumbersome.
Therefore, directly create a new org.apache.calcite.rel.metadata.RelMdColumnOrigins class under this project, copy the source code of Calcite and modify it.

Remember to add getColumnOrigins(Snapshot rel, RelMetadataQuery mq, int iOutputColumn) that supports Lookup Join.
```java
  /**
   * Support the field blood relationship of lookup join
   */
  public Set<RelColumnOrigin> getColumnOrigins(Snapshot rel,
                                               RelMetadataQuery mq, int iOutputColumn) {
      return mq.getColumnOrigins(rel.getInput(), iOutputColumn);
  }
```
### 5.2 Support Table Functions

#### 5.2.1 New UDTF

- Custom Table Function class

```java
@FunctionHint(output = @DataTypeHint("ROW<word STRING, length INT>"))
public class MySplitFunction extends TableFunction<Row> {

    public void eval(String str) {
        for (String s : str.split(" ")) {
            // use collect(...) to emit a row
            collect(Row.of(s, s.length()));
        }
    }
}
```

- New my_split_udtf function

```sql
DROP FUNCTION IF EXISTS my_split_udtf;

CREATE FUNCTION IF NOT EXISTS my_split_udtf 
  AS 'com.dtwave.flink.lineage.tablefuncion.MySplitFunction';
```

#### 5.2.2 Test UDTF SQL

```sql
INSERT INTO
  dwd_hudi_users
SELECT
  length,
  name,
  word as company_name,
  birthday,
  ts,
  DATE_FORMAT(birthday, 'yyyyMMdd')
FROM
  ods_mysql_users,
  LATERAL TABLE (my_split_udtf (name))
```

#### 5.2.3 Analyze Optimized Logical Plan
Generate Optimized Logical Plan as follows:

```shell
 FlinkLogicalCalc(select=[length, name, word AS company_name, birthday, ts, DATE_FORMAT(birthday, _UTF-16LE'yyyyMMdd') AS EXPR$5])
  FlinkLogicalCorrelate(correlation=[$cor0], joinType=[inner], requiredColumns=[{1}])
    FlinkLogicalCalc(select=[id, name, birthday, ts, PROCTIME() AS proc_time])
      FlinkLogicalTableSourceScan(table=[[hive, flink_demo, ods_mysql_users]], fields=[id, name, birthday, ts])
    FlinkLogicalTableFunctionScan(invocation=[my_split_udtf($cor0.name)], rowType=[RecordType:peek_no_expand(VARCHAR(2147483647) word, INTEGER length)])
```

You can see that FlinkLogicalCorrelate is generated in the middle, and the variable information during source code debugging is as follows:
![5.2 Table Function debugging variable.png](https://github.com/HamaWhiteGG/flink-sql-lineage/blob/main/data/images/5.2%20Table%20Function%20debugging%20variable.png)

Analyze inheritance relationships:

```bash
# FlinkLogicalCorrelate
FlinkLogicalCorrelate -> Correlate -> BiRel -> AbstractRelNode -> RelNode

# Join (Join is similar to Correlate, also shown here)
Join -> BiRel -> AbstractRelNode -> RelNode

# FlinkLogicalTableSourceScan
FlinkLogicalTableSourceScan -> TableScan ->AbstractRelNode -> RelNode
	      
# FlinkLogicalTableFunctionScan
FlinkLogicalTableFunctionScan -> TableFunctionScan ->AbstractRelNode -> RelNode	     
```

#### 5.2.4 Added getColumnOrigins(Correlate rel, RelMetadataQuery mq, int iOutputColumn) method
In the getColumnOrigins() method of the org.apache.calcite.rel.metadata.RelMdColumnOrigins class, it is found that there is no Correlate method as a parameter, so the field blood relationship of UDTF cannot be resolved.


Since both Correlate and Join inherit from BiRel, there are two RelNodes left and right. Therefore, when writing the analysis of Correlate, you can refer to the existing getColumnOrigins(Join rel, RelMetadataQuery mq, int iOutputColumn) method.

The two fields word and length of the temporary table generated by LATERAL TABLE (my_split_udtf (name)) are essentially the name field from the dwd_hudi_users table.
Therefore, the fields in the UDTF are obtained for the LATERAL TABLE on the right, and then the left table information and indexes are obtained according to the field names, and finally the field blood relationship of the left table is obtained.


The core code is as follows:

```java
/**
 * Support the field blood relationship of table function
 */
public Set<RelColumnOrigin> getColumnOrigins(Correlate rel, RelMetadataQuery mq, int iOutputColumn) {

    List<RelDataTypeField> leftFieldList = rel.getLeft().getRowType().getFieldList();

    int nLeftColumns = leftFieldList.size();
    Set<RelColumnOrigin> set;
    if (iOutputColumn < nLeftColumns) {
        set = mq.getColumnOrigins(rel.getLeft(), iOutputColumn);
    } else {
        // get the field name of the left table configured in the Table Function on the right
        TableFunctionScan tableFunctionScan = (TableFunctionScan) rel.getRight();
        RexCall rexCall = (RexCall) tableFunctionScan.getCall();
        // support only one field in table function
        RexFieldAccess rexFieldAccess = (RexFieldAccess) rexCall.operands.get(0);
        String fieldName = rexFieldAccess.getField().getName();

        int leftFieldIndex = 0;
        for (int i = 0; i < nLeftColumns; i++) {
            if (leftFieldList.get(i).getName().equalsIgnoreCase(fieldName)) {
                leftFieldIndex = i;
                break;
            }
        }
        /**
         * Get the fields from the left table, don't go to
         * getColumnOrigins(TableFunctionScan rel,RelMetadataQuery mq, int iOutputColumn),
         * otherwise the return is null, and the UDTF field origin cannot be parsed
         */
        set = mq.getColumnOrigins(rel.getLeft(), leftFieldIndex);
    }
    return set;
}
```
> Note: In the Logical Plan, you can see that the right RelNode is of the FlinkLogicalTableFunctionScan type, inherited from TableFunctionScan, but the result obtained by the existing getColumnOrigins(TableFunctionScan rel,RelMetadataQuery mq, int iOutputColumn) is null.
At the beginning, I also tried to modify this method, but I have been unable to obtain the information of the left table. So change to getColumnOrigins(Correlate rel, RelMetadataQuery mq, int iOutputColumn) to get the code of right-changing LATERAL TABLE origin.

#### 5.2.5 Test Result

| **sourceTable** | **sourceColumn** | **targetTable** | **targetColumn** |
| --- | --- | --- | --- |
| ods_mysql_users | name | dwd_hudi_users | id |
| ods_mysql_users | name | dwd_hudi_users | name |
| ods_mysql_users | name | dwd_hudi_users | company_name |
| ods_mysql_users | birthday | dwd_hudi_users | birthday |
| ods_mysql_users | ts | dwd_hudi_users | ts |
| ods_mysql_users | birthday | dwd_hudi_users | partition |

> Note: The word and length in SQL are essentially from the name field of the dwd_hudi_users table, so the field relationship shows name.
  That is ods_mysql_users.name -> length -> dwd_hudi_users.id and ods_mysql_users.name -> word -> dwd_hudi_users.company_name
	 
### 5.3 Support Watermark
#### 5.3.1 New ods_mysql_users_watermark

```sql
DROP TABLE IF EXISTS ods_mysql_users_watermark;

CREATE TABLE ods_mysql_users_watermark(
  id BIGINT,
  name STRING,
  birthday TIMESTAMP(3),
  ts TIMESTAMP(3),
  proc_time as proctime(),
  WATERMARK FOR ts AS ts - INTERVAL '5' SECOND
) WITH (
  'connector' = 'mysql-cdc',
  'hostname' = '192.168.90.xxx',
  'port' = '3306',
  'username' = 'root',
  'password' = 'xxx',
  'server-time-zone' = 'Asia/Shanghai',
  'database-name' = 'demo',
  'table-name' = 'users'
);
```

#### 5.3.2 Test Watermark SQL

```sql
INSERT INTO
    dwd_hudi_users
SELECT
    id,
    name,
    name as company_name,
    birthday,
    ts,
    DATE_FORMAT(birthday, 'yyyyMMdd')
FROM
    ods_mysql_users_watermark
```

#### 5.3.3 Analyze Optimized Logical Plan
The Optimized Logical Plan is generated as follows:
```shell
 FlinkLogicalCalc(select=[id, name, name AS company_name, birthday, ts, DATE_FORMAT(birthday, _UTF-16LE'yyyyMMdd') AS EXPR$5])
  FlinkLogicalWatermarkAssigner(rowtime=[ts], watermark=[-($3, 5000:INTERVAL SECOND)])
    FlinkLogicalTableSourceScan(table=[[hive, flink_demo, ods_mysql_users_watermark]], fields=[id, name, birthday, ts])
```
You can see that FlinkLogicalWatermarkAssigner is generated in the middle, and the inheritance relationship is analyzed:
```bash
FlinkLogicalWatermarkAssigner -> WatermarkAssigner -> SingleRel -> AbstractRelNode -> RelNode
```

Therefore, the getColumnOrigins method with SingleRel as a parameter is added below.

#### 5.3.4 Add getColumnOrigins(SingleRel rel, RelMetadataQuery mq, int iOutputColumn) method
```java
 /**
   * Support the field blood relationship of watermark
   */
  public Set<RelColumnOrigin> getColumnOrigins(SingleRel rel,
                                               RelMetadataQuery mq, int iOutputColumn) {
      return mq.getColumnOrigins(rel.getInput(), iOutputColumn);
  } 
```
#### 5.3.5 Test Result

| **sourceTable** | **sourceColumn** | **targetTable** | **targetColumn** |
| --- | --- | --- | --- |
| ods_mysql_users_watermark | id | dwd_hudi_users | id |
| ods_mysql_users_watermark | name | dwd_hudi_users | name |
| ods_mysql_users_watermark | name | dwd_hudi_users | company_name |
| ods_mysql_users_watermark | birthday | dwd_hudi_users | birthday |
| ods_mysql_users_watermark | ts | dwd_hudi_users | ts |
| ods_mysql_users_watermark | birthday | dwd_hudi_users | partition |


## 六、References

1. [How to screw SQL to anything with Apache Calcite](https://zephyrnet.com/how-to-screw-sql-to-anything-with-apache-calcite/)
2. [Publish to mavenLocal using build.gradle.kts](https://www.javaroad.cn/questions/71299)
3. [Flink SQL LookupJoin Ultimate Solution and Getting Started with Flink Rule](https://zhuanlan.zhihu.com/p/546080679)
4. [Analyzing Flink SQL column-level data lineage based on Calcite](https://blog.csdn.net/nazeniwaresakini/article/details/121652104)
5. [Dry goods | Detailed explanation of FlinkSQL implementation principle](https://www.modb.pro/db/133495)
6. [SQL parsing framework: Calcite](https://www.liaojiayi.com/calcite/)
7. [Flink1.14-table functions doc](https://nightlies.apache.org/flink/flink-docs-release-1.14/docs/dev/table/functions/udfs/#table-functions)

