package ghidra.app.cmd.data.rtti.gcc.typeinfo;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import ghidra.app.util.NamespaceUtils;
import ghidra.app.cmd.data.rtti.ClassTypeInfo;
import ghidra.app.cmd.data.rtti.gcc.ClassTypeInfoUtils;
import ghidra.app.cmd.data.rtti.gcc.TypeInfoUtils;
import ghidra.app.cmd.data.rtti.gcc.VtableModel;
import ghidra.app.cmd.data.rtti.gcc.typeinfo.AbstractTypeInfoModel;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeComponent;
import ghidra.program.model.data.DataTypeConflictHandler;
import ghidra.program.model.data.DataTypeManager;
import ghidra.program.model.data.InvalidDataTypeException;
import ghidra.program.model.data.Pointer;
import ghidra.program.model.data.Structure;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.GhidraClass;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.util.InvalidNameException;
import ghidra.util.Msg;
import ghidra.util.exception.CancelledException;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;
import ghidra.util.task.TaskMonitor;

import static ghidra.program.model.data.Undefined.isUndefined;
import static ghidra.app.cmd.data.rtti.gcc.GnuUtils.PURE_VIRTUAL_FUNCTION_NAME;

/**
 * Base Model for __class_type_info and its derivatives.
 */
public abstract class AbstractClassTypeInfoModel extends AbstractTypeInfoModel implements ClassTypeInfo {

    private static final String VPTR = "_vptr";
    protected VtableModel vtable = null;

    protected AbstractClassTypeInfoModel(Program program, Address address) {
        super(program, address);
    }

    private static String getUniqueTypeName(ClassTypeInfo type) throws InvalidDataTypeException {
        StringBuilder builder = new StringBuilder(type.getTypeName());
        for (ClassTypeInfo parent : type.getParentModels()) {
            builder.append(parent.getTypeName());
        }
        return builder.toString();
    }

    @Override
    public String getUniqueTypeName() throws InvalidDataTypeException {
        return getUniqueTypeName(this);
    }

    @Override
    public VtableModel getVtable(TaskMonitor monitor) throws InvalidDataTypeException {
        if (vtable != null) {
            return vtable;
        }
        SymbolTable table = program.getSymbolTable();
        for (Symbol symbol : table.getSymbols(VtableModel.SYMBOL_NAME, getGhidraClass())) {
                vtable = new VtableModel(program, symbol.getAddress(), this);
                try {
                    vtable.validate();
                    return vtable;
                } catch (InvalidDataTypeException e) {
                    continue;
                }
        }
        try {
            vtable = (VtableModel) ClassTypeInfoUtils.findVtable(program, address, monitor);
        } catch (CancelledException e) {
            vtable = VtableModel.NO_VTABLE;
        }
        return vtable;
    }

    @Override
    public boolean isAbstract() throws InvalidDataTypeException {
        validate();
        try {
            for (Function[] functionTable : getVtable().getFunctionTables()) {
                for (Function function : functionTable) {
                    if (function == null || function.getName().equals(PURE_VIRTUAL_FUNCTION_NAME)) {
                        return true;
                    }
                }
            }
        } catch (InvalidDataTypeException e) {}
        return false;
    }

    @Override
    public GhidraClass getGhidraClass() throws InvalidDataTypeException {
        validate();
        if (!(namespace instanceof GhidraClass)) {
            try {
                if (namespace.getSymbol().checkIsValid()) {
                    namespace = NamespaceUtils.convertNamespaceToClass(namespace);
                } else {
                    namespace = TypeInfoUtils.getNamespaceFromTypeName(program, typeName);
                    namespace = NamespaceUtils.convertNamespaceToClass(namespace);
                }
            } catch (InvalidInputException e) {
                Msg.error(this, e);
                return null;
            }
        } return (GhidraClass) namespace;
    }

    protected void setSuperStructureCategoryPath(Structure struct)
        throws InvalidDataTypeException {
            try {
                struct.setCategoryPath(getClassDataType().getCategoryPath());
                struct.setName(SUPER+struct.getName());
            } catch (InvalidNameException | DuplicateNameException e) {
                Msg.error(
                    this, "Failed to change placeholder struct "+getName()+"'s CategoryPath", e);
            }
    }

    protected Structure getSuperClassDataType() throws InvalidDataTypeException {
        DataTypeManager dtm = program.getDataTypeManager();
        Structure struct = getClassDataType();
        try {
            long[] offsets = ((VtableModel) getVtable()).getBaseOffsetArray();
            if (offsets.length == 1) {
                // finished
                return struct;
            }
            Structure superStruct = (Structure) struct.copy(dtm);
            setSuperStructureCategoryPath(superStruct);
            deleteVirtualComponents(superStruct);
            trimStructure(superStruct);
            return resolveStruct(superStruct);
        } catch (InvalidDataTypeException e) {
            return struct;
        }
    }

    private void clearComponent(Structure struct, int length, int offset) {
        if (offset >= struct.getLength()) {
            return;
        }
        for (int size = 0; size < length;) {
            DataTypeComponent comp = struct.getComponentAt(offset);
            if (comp!= null) {
                size += comp.getLength();
            } else {
                size++;
            }
            struct.deleteAtOffset(offset);
        }
    }

    protected void replaceComponent(Structure struct, DataType parent, String name, int offset) {
        clearComponent(struct, parent.getLength(), offset);
        struct.insertAtOffset(offset, parent, parent.getLength(), name, null);
    }

    protected void addVptr(Structure struct) {
        try {
            getVtable().validate();
        } catch (InvalidDataTypeException e) {
            return;
        }
        DataType vptr = ClassTypeInfoUtils.getVptrDataType(program, this);
        DataTypeComponent comp = struct.getComponentAt(0);
        if (comp == null || isUndefined(comp.getDataType())) {
            if (vptr != null) {
                clearComponent(struct, program.getDefaultPointerSize(), 0);
                struct.insertAtOffset(0, vptr, program.getDefaultPointerSize(), VPTR, null);
            }
        } else if (comp.getFieldName() == null || !comp.getFieldName().startsWith(SUPER)) {
            clearComponent(struct, program.getDefaultPointerSize(), 0);
            struct.insertAtOffset(0, vptr, program.getDefaultPointerSize(), VPTR, null);
        }
    }

    protected void removeVptr(Structure struct) {
        try {
            getVtable().validate();
            DataTypeComponent comp = struct.getComponentAt(0);
            if (comp.getFieldName().equals(VPTR) && comp.getDataType() instanceof Pointer) {
                struct.deleteAtOffset(0);
            }
        } catch (InvalidDataTypeException e) {
            return;
        }
    }

    protected static void trimStructure(Structure struct) {
        DataTypeComponent[] comps = struct.getDefinedComponents();
        if (comps.length == 0) {
            return;
        }
        int endOffset =  comps[comps.length-1].getEndOffset()+1;
        while (struct.getLength() > endOffset) {
            struct.deleteAtOffset(endOffset);
        }
    }

    protected static Structure resolveStruct(Structure struct) {
        DataTypeManager dtm = struct.getDataTypeManager();
        return (Structure) dtm.resolve(struct, DataTypeConflictHandler.REPLACE_HANDLER);
    }

    @Override
    public DataType getRepresentedDataType() throws InvalidDataTypeException {
        return getClassDataType(false);
    }

    @Override
    public Set<ClassTypeInfo> getVirtualParents() throws InvalidDataTypeException {
        return Collections.emptySet();
    }

    protected void deleteVirtualComponents(Structure struct) throws InvalidDataTypeException {
        Set<Structure> parents = new HashSet<>();
        for (ClassTypeInfo parent : getVirtualParents()) {
            Structure parentStruct =
                ((AbstractClassTypeInfoModel) parent).getSuperClassDataType();
            parents.add(parentStruct);
            parents.add(parent.getClassDataType());
        }
        DataTypeComponent[] comps = struct.getDefinedComponents();
        for (DataTypeComponent comp : comps) {
            DataType dt = comp.getDataType();
            if (parents.contains(dt)) {
                int ordinal = comp.getOrdinal();
                int numComponents = struct.getNumComponents() - 1;
                int[] ordinals = IntStream.rangeClosed(ordinal, numComponents).toArray();
                struct.delete(ordinals);
                break;
            }
        }
    }
}