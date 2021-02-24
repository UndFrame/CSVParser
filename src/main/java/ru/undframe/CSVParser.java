package ru.undframe;

import org.reflections.Reflections;
import ru.undframe.field.FieldParser;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.*;

public class CSVParser implements Parser {

    private Map<Class, CSVTable> csvObjects = new HashMap<>();
    private Map<Object, ArrayTable> csvTables = new HashMap<>();
    private Map<Class, ru.undframe.field.Field> fieldParsers = new HashMap<>();
    private Map<Class, DataReader> csvReaders = new HashMap<>();

    @Override
    public void launch(String pac) throws IllegalAccessException, InstantiationException, IOException {
        Reflections reflections = new Reflections(pac);


            List<Class<?>> readers = new ArrayList<>(reflections.getTypesAnnotatedWith(CSVReader.class));

            readers.sort((o1, o2) -> {
                CSVReader o1Annotation = o1.getAnnotation(CSVReader.class);
                CSVReader o2Annotation = o2.getAnnotation(CSVReader.class);
                return Integer.compare(o1Annotation.prority(), o2Annotation.prority());
            });

            for (Class<?> readerClass : readers) {
                if (Arrays.asList(readerClass.getInterfaces()).contains(DataReader.class)) {
                    DataReader reader = (DataReader) readerClass.newInstance();
                    CSVReader csvReader = readerClass.getDeclaredAnnotation(CSVReader.class);
                    for (Class aClass : csvReader.supportClasses()) {
                        csvReaders.put(aClass, reader);
                    }
                }
            }

            List<Class<?>> fieldParsers = new ArrayList<>(reflections.getTypesAnnotatedWith(FieldParser.class));

            fieldParsers.sort((o1, o2) -> {
                FieldParser o1Annotation = o1.getAnnotation(FieldParser.class);
                FieldParser o2Annotation = o2.getAnnotation(FieldParser.class);
                return Integer.compare(o1Annotation.prority(), o2Annotation.prority());
            });

            for (Class<?> fieldParser : fieldParsers) {
                if (Arrays.asList(fieldParser.getInterfaces()).contains(ru.undframe.field.Field.class)) {

                    ru.undframe.field.Field<?> field = (ru.undframe.field.Field) fieldParser.newInstance();
                    FieldParser parses = fieldParser.getDeclaredAnnotation(FieldParser.class);
                    for (Class aClass : parses.parseClasses()) {
                        this.fieldParsers.put(aClass, field);
                    }
                }
            }

            List<Class<?>> typesAnnotatedWith = new ArrayList<>(reflections.getTypesAnnotatedWith(CSVData.class));

            typesAnnotatedWith.sort((o1, o2) -> {
                CSVData o1Annotation = o1.getAnnotation(CSVData.class);
                CSVData o2Annotation = o2.getAnnotation(CSVData.class);
                return Integer.compare(o1Annotation.priority(), o2Annotation.priority());
            });

            for (Class<?> aClass : typesAnnotatedWith) {
                for (Annotation declaredAnnotation : aClass.getDeclaredAnnotations()) {
                    if (declaredAnnotation instanceof CSVData) {
                        CSVTable csvObject = instanceCSVObject(aClass);
                        registerCSV(aClass, csvObject);
                    }
                }
            }

    }

    private static Parser parser;

    public static Parser getInstance() {
        if (parser == null)
            parser = new CSVParser();
        return parser;
    }

    void registerCSV(Class c, CSVTable csv) {
        csvObjects.put(c, csv);
    }

    @Override
    public void refreshCSVs() throws IOException {

        List<Object> objects = new ArrayList<>(csvTables.keySet());

        csvTables.clear();


        for (CSVTable csv : csvObjects.values()) {
            csv.refreshData();
        }
    }

    public DataReader getReaderCSV(Class c) {
        return csvReaders.get(c);
    }

    @Override
    public CSVTable getCSVObject(Class c) {
        return csvObjects.get(c);
    }

    private CSVTable instanceCSVObject(Class aClass) throws IllegalAccessException, InstantiationException, IOException {
        CSVTable csvObject = null;

        Object instanceClass = aClass.newInstance();

        for (Annotation declaredAnnotation : aClass.getDeclaredAnnotations()) {
            if (declaredAnnotation instanceof CSVData) {
                CSVData data = (CSVData) declaredAnnotation;

                List<CSVColumn> csvColumns = new ArrayList<>();

                for (Field field : aClass.getDeclaredFields()) {
                    for (Annotation annotation : field.getDeclaredAnnotations()) {
                        if (annotation instanceof Column) {
                            Column column = (Column) annotation;
                            Parsable parsable = field.getAnnotation(Parsable.class);
                            Position head = Position.of(column.head());
                            field.setAccessible(true);
                            Object defaultValue = field.get(instanceClass);
                            field.setAccessible(false);
                            CSVColumn csvColumn = new CSVColumn(
                                    field.getName(),
                                    parsable != null ? parsable.parser().newInstance() : getParser(field.getType()),
                                    head, column.main(),
                                    defaultValue, column.link(),
                                    column.constantPosition()
                            );

                            DataLoader dataLoader = field.getAnnotation(DataLoader.class);
                            if (dataLoader != null) {
                                CSVTable fromCSV = csvObjects.getOrDefault(dataLoader.fromCSV(), null);



                                if (fromCSV != null) {
                                    csvColumn.setFromTable(fromCSV.getData());
                                } else
                                    throw new IllegalArgumentException("Please change priority load " + dataLoader.fromCSV() + " to a less");
                            }
                            csvColumns.add(csvColumn);
                        }
                    }
                }

                Object csvGetter = null;
                DataReader dataReader = null;


                CSVDataReader dataReaderInfo= (CSVDataReader) aClass.getDeclaredAnnotation(CSVDataReader.class);

                if(instanceClass instanceof CSVSupplier) {
                    csvGetter = ((CSVSupplier) instanceClass).getCSV();
                }else{

                    CSVGetterFromLink linkInfo = (CSVGetterFromLink) aClass.getDeclaredAnnotation(CSVGetterFromLink.class);
                    if(linkInfo!=null)
                        csvGetter = linkInfo.link();

                }

                if(dataReaderInfo!=null)
                    dataReader = dataReaderInfo.reader().newInstance();
                else if (csvGetter != null) {
                    dataReader = csvReaders.getOrDefault(csvGetter.getClass(), null);
                }

                csvObject = new CSVTable(csvGetter,
                        dataReader,
                        csvColumns, aClass);
            }
        }

        return csvObject;
    }

    @Override
    public void registerParser(Class c, ru.undframe.field.Field field) {
        this.fieldParsers.put(c, field);
    }

    private ru.undframe.field.Field getParser(Class c) {
        if (fieldParsers.containsKey(c))
            return fieldParsers.get(c);

        throw new IllegalArgumentException("Class " + c.getName() + " don`t support");
    }

   /* private ArrayTable getData(Object o) {
        return csvTables.computeIfAbsent(o, u -> {
            try {
                return getReaderCSV(o.getClass()).read(u);
            } catch (IOException e) {
                e.printStackTrace();
            }
            throw new IllegalArgumentException();
        });
    }*/

}
