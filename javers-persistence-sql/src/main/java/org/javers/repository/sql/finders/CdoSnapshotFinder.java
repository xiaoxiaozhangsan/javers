package org.javers.repository.sql.finders;

import org.javers.common.collections.Optional;
import org.javers.core.commit.CommitId;
import org.javers.core.commit.CommitMetadata;
import org.javers.core.json.JsonConverter;
import org.javers.core.metamodel.clazz.ManagedClass;
import org.javers.core.metamodel.object.CdoSnapshot;
import org.javers.core.metamodel.object.GlobalId;
import org.javers.core.metamodel.object.SnapshotType;
import org.javers.core.metamodel.property.Property;
import org.javers.repository.sql.finders.PropertiesFinder.JvSnapshotProperty;
import org.javers.repository.sql.infrastructure.poly.JaversPolyJDBC;
import org.joda.time.LocalDateTime;
import org.polyjdbc.core.query.Order;
import org.polyjdbc.core.query.SelectQuery;
import org.polyjdbc.core.query.mapper.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.javers.repository.sql.domain.FixedSchemaFactory.*;

public class CdoSnapshotFinder {

    private final JaversPolyJDBC javersPolyJDBC;
    private final PropertiesFinder propertiesFinder;
    private JsonConverter jsonConverter;

    public CdoSnapshotFinder(JaversPolyJDBC javersPolyJDBC, PropertiesFinder propertiesFinder) {
        this.javersPolyJDBC = javersPolyJDBC;
        this.propertiesFinder = propertiesFinder;
    }

    public Optional<CdoSnapshot> getLatest(GlobalId globalId) {
        Optional<String> snapshotPk = selectSnapshotPrimaryKey(globalId);
        
        if (snapshotPk.isEmpty()) {
            return Optional.empty();
        }

        JvCommitDto jvCommitDto = selectCommitMetadata(snapshotPk);
        List<JvSnapshotProperty> properties = propertiesFinder.findProperties(Integer.valueOf(snapshotPk.get()));
        Map<Property, Object> prop = propertiesAsMap(globalId.getCdoClass(), properties);

        CommitMetadata commitMetadata = new CommitMetadata(jvCommitDto.author, jvCommitDto.date, CommitId.valueOf(jvCommitDto.commitId));
        JvSnapshotDto jvSnapshotDto = new JvSnapshotDto(globalId, commitMetadata, prop, jvCommitDto.snapshotType);

        return Optional.of(jsonConverter.fromJson(jsonConverter.toJson(jvSnapshotDto), CdoSnapshot.class));
    }

    public List<CdoSnapshot> getStateHistory(GlobalId cdoId, String className, int limit) {

        SelectQuery query = javersPolyJDBC.query()
                .select(SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_PK+ ", " + SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_TYPE + ", " + COMMIT_TABLE_NAME + "." + COMMIT_TABLE_AUTHOR + ", " + COMMIT_TABLE_NAME + "." + COMMIT_TABLE_COMMIT_DATE + ", " + COMMIT_TABLE_COMMIT_ID)
                .from(SNAPSHOT_TABLE_NAME +
                        " INNER JOIN " + COMMIT_TABLE_NAME + " ON " + COMMIT_TABLE_NAME + "." + COMMIT_TABLE_PK + "=" + SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_COMMIT_FK +
                        " INNER JOIN " + GLOBAL_ID_TABLE_NAME + " ON " + GLOBAL_ID_TABLE_NAME + "." + GLOBAL_ID_PK + "=" + SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_GLOBAL_ID_FK +
                        " INNER JOIN " + CDO_CLASS_TABLE_NAME + " ON " + CDO_CLASS_TABLE_NAME + "." + CDO_CLASS_PK + "=" + GLOBAL_ID_TABLE_NAME + "." + GLOBAL_ID_CLASS_FK)
                .where(GLOBAL_ID_TABLE_NAME + "." + GLOBAL_ID_LOCAL_ID + " = :localId AND " + CDO_CLASS_TABLE_NAME + "." + CDO_CLASS_QUALIFIED_NAME + " = :qualifiedName")
                .orderBy(SNAPSHOT_TABLE_PK, Order.DESC)
                .limit(limit)
                .withArgument("localId", jsonConverter.toJson(cdoId.getCdoId()))
                .withArgument("qualifiedName", className);

        List<JvCommitDto> jvCommitDtos = javersPolyJDBC.queryRunner().queryList(query, new ObjectMapper<JvCommitDto>() {
            @Override
            public JvCommitDto createObject(ResultSet resultSet) throws SQLException {
                return new JvCommitDto(resultSet.getInt(SNAPSHOT_TABLE_PK), resultSet.getString(SNAPSHOT_TABLE_TYPE), resultSet.getString(COMMIT_TABLE_AUTHOR), resultSet.getTimestamp(COMMIT_TABLE_COMMIT_DATE), resultSet.getString(COMMIT_TABLE_COMMIT_ID));
            }
        });

        List<CdoSnapshot> snapshots = new ArrayList<>();
        
        for (JvCommitDto jvCommitDto: jvCommitDtos) {
            //TODO n + 1 problem
            List<JvSnapshotProperty> properties = propertiesFinder.findProperties(jvCommitDto.snapshotPk);
            Map<Property, Object> prop = propertiesAsMap(cdoId.getCdoClass(), properties);
            
            CommitMetadata commitMetadata = new CommitMetadata(jvCommitDto.author, jvCommitDto.date, CommitId.valueOf(jvCommitDto.commitId));
            JvSnapshotDto jvSnapshotDto = new JvSnapshotDto(cdoId, commitMetadata, prop, jvCommitDto.snapshotType);
            snapshots.add(jsonConverter.fromJson(jsonConverter.toJson(jvSnapshotDto), CdoSnapshot.class));
        }
        
        return snapshots;
    }

    private JvCommitDto selectCommitMetadata(Optional<String> snapshotPk) {
        SelectQuery selectQuery2 = javersPolyJDBC.query()
                .select(SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_TYPE + ", " + COMMIT_TABLE_NAME + "." + COMMIT_TABLE_AUTHOR + ", " + COMMIT_TABLE_NAME + "." + COMMIT_TABLE_COMMIT_DATE + ", " + COMMIT_TABLE_NAME + "." + COMMIT_TABLE_COMMIT_ID)
                .from(SNAPSHOT_TABLE_NAME +
                        " INNER JOIN " + COMMIT_TABLE_NAME + " ON " + COMMIT_TABLE_NAME + "." + COMMIT_TABLE_PK + "=" + SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_COMMIT_FK)
                .where(SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_PK + " = :snapshotPk")
                .withArgument("snapshotPk", Integer.valueOf(snapshotPk.get()));

        List<JvCommitDto> jvCommitDto = javersPolyJDBC.queryRunner().queryList(selectQuery2, new ObjectMapper<JvCommitDto>() {
            @Override
            public JvCommitDto createObject(ResultSet resultSet) throws SQLException {
                return new JvCommitDto(resultSet.getString(SNAPSHOT_TABLE_TYPE), resultSet.getString(COMMIT_TABLE_AUTHOR), resultSet.getTimestamp(COMMIT_TABLE_COMMIT_DATE), resultSet.getString(COMMIT_TABLE_COMMIT_ID));
            }
        });
        
        return jvCommitDto.get(0);
    }

    private Optional<String> selectSnapshotPrimaryKey(GlobalId globalId) {
        SelectQuery selectQuery = javersPolyJDBC.query()
                .select("MAX(" + SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_PK + ") AS " + SNAPSHOT_TABLE_PK)
                .from(SNAPSHOT_TABLE_NAME +
                        " INNER JOIN " + GLOBAL_ID_TABLE_NAME + " ON " + SNAPSHOT_TABLE_NAME + "." + SNAPSHOT_TABLE_GLOBAL_ID_FK + "=" + GLOBAL_ID_TABLE_NAME + "." + GLOBAL_ID_PK +
                        " INNER JOIN " + CDO_CLASS_TABLE_NAME + " ON " + GLOBAL_ID_TABLE_NAME + "." + GLOBAL_ID_CLASS_FK + "=" + CDO_CLASS_TABLE_NAME + "." + CDO_CLASS_PK)
                .where(GLOBAL_ID_TABLE_NAME + "." + GLOBAL_ID_LOCAL_ID + " = :localId AND " + CDO_CLASS_TABLE_NAME + "." + CDO_CLASS_QUALIFIED_NAME + " = :qualifiedName")
                .withArgument("localId", jsonConverter.toJson(globalId.getCdoId()))
                .withArgument("qualifiedName", globalId.getCdoClass().getName());

        
        List<String> snapshotPk = javersPolyJDBC.queryRunner().queryList(selectQuery, new ObjectMapper<String>() {
            @Override
            public String createObject(ResultSet resultSet) throws SQLException {
                return resultSet.getString(SNAPSHOT_TABLE_PK);
            }
        });

        if (snapshotPk.size() != 1 || (snapshotPk.size() == 1 && snapshotPk.get(0) == null)) {
            return Optional.empty();
        }
        
        return Optional.of(snapshotPk.get(0));
    }

    private Map<Property, Object> propertiesAsMap(ManagedClass cdoClass, List<JvSnapshotProperty> state) {
        Map<Property, Object> stateAsMap = new HashMap<>();

        for (JvSnapshotProperty prop : state) {
            stateAsMap.put(cdoClass.getProperty(prop.name), prop.value);
        }

        return stateAsMap;
    }

    public void setJsonConverter(JsonConverter jsonConverter) {
        this.jsonConverter = jsonConverter;
    }

    private static class JvSnapshotDto {
        GlobalId globalId;
        CommitMetadata commitMetadata;
        Map<Property, Object> state;
        SnapshotType type;

        public JvSnapshotDto(GlobalId globalId, CommitMetadata commitMetadata, Map<Property, Object> state, SnapshotType type) {
            this.globalId = globalId;
            this.commitMetadata = commitMetadata;
            this.state = state;
            this.type = type;
        }

    }

    private static class JvCommitDto {
        SnapshotType snapshotType;
        String author;
        LocalDateTime date;
        String commitId;
        int snapshotPk;

        private JvCommitDto(String snapshotType, String author, Timestamp date, String commmitId) {
            this.snapshotType = SnapshotType.valueOf(snapshotType);
            this.author = author;
            this.date = new LocalDateTime(date.getTime());
            this.commitId = commmitId;
        }
        
        private JvCommitDto(int snapshotPk, String snapshotType, String author, Timestamp date, String commmitId) {
            this.snapshotPk = snapshotPk;
            this.snapshotType = SnapshotType.valueOf(snapshotType);
            this.author = author;
            this.date = new LocalDateTime(date.getTime());
            this.commitId = commmitId;
        }
        
    }
}
