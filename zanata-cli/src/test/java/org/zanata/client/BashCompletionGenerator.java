package org.zanata.client;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.AccessibleObject;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import org.apache.commons.io.FileUtils;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zanata.client.commands.BasicOptions;

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

/**
 * @author Patrick Huang <a
 *         href="mailto:pahuang@redhat.com">pahuang@redhat.com</a>
 */
public class BashCompletionGenerator {
    private static final Logger log = LoggerFactory
            .getLogger(BashCompletionGenerator.class);

    private static final Joiner joiner = Joiner.on(" ");

    private List<String> baseCommands;
    private List<Option> genericOptions;
    private Map<String, List<Option>> commandOptions = Maps.newHashMap();
    private Set<Option> allOptions = Sets.newHashSet();
    private String commandName;
    private String commandDescription;

    public static void main(String[] args) throws IOException {
        BashCompletionGenerator generator = new BashCompletionGenerator();
        File to;
        if (args.length >= 1) {
            to = new File(args[0]);
        } else {
            to = new File(".", "zanata-cli-completion");
        }
        File headerFile;
        if (args.length >= 2) {
            headerFile = new File(args[1]);
        } else {
            headerFile = new File(".", "COPYING-header.txt");
        }
        generator.generateFile(to, headerFile);
    }

    public BashCompletionGenerator() {
        ImmutableMap<String, Class<BasicOptions>> commands =
                ZanataClient.OPTIONS;
        baseCommands = ImmutableList.copyOf(commands.keySet());
        commandName = ZanataClient.COMMAND_NAME;
        commandDescription = ZanataClient.COMMAND_DESCRIPTION;
        genericOptions = getOptions(ZanataClient.class);
        allOptions.addAll(genericOptions);

        for (String commandName : commands.keySet()) {
            List<Option> options = getOptions(commands.get(commandName));
            // do we still want generic options to appear?
            // options.removeAll(genericOptions);
            commandOptions.put(commandName, options);
            allOptions.addAll(options);
        }
    }

    private static List<Option> getOptions(Class<?> bean) {
        ImmutableList.Builder<Option> allOptions = ImmutableList.builder();
        // recursively process all the methods/fields.
        for (Class<?> c = bean; c != null; c = c.getSuperclass()) {
            ImmutableList.Builder<AccessibleObject> builder =
                    ImmutableList.builder();
            List<AccessibleObject> fieldAndMethods =
                    builder.add(c.getDeclaredFields())
                            .add(c.getDeclaredMethods()).build();
            for (AccessibleObject accessibleObject : fieldAndMethods) {
                Option option = accessibleObject.getAnnotation(Option.class);
                if (option != null) {
                    allOptions.add(option);
                }
            }
        }
        return allOptions.build();
    }

    private void generateFile(File to, File headerFile) throws IOException {
        log.info("writing bash completion file to {}", to);
        // if we can use groovy here then here doc will be really handy...
        String commands = joiner.join(baseCommands);
        // String genericOptions = optionsToString(generator.genericOptions);

        // TODO write to a PrintStream instead of an ArrayList
        List<String> lines = Lists.newArrayList();

        List<String> license = Files.readLines(headerFile, Charsets.UTF_8);
        for (String line : license) {
            lines.add(line.isEmpty() ? "#" : "# " + line);
        }

        lines.add("#");
        lines.add("# Completion for " + commandDescription);
        lines.add("# Generated by "
                + BashCompletionGenerator.class.getSimpleName());
        lines.add("#");

        lines.add("_zanata()");
        lines.add("{");
        lines.add("    local cur prev opts base cmds");
        lines.add("    COMPREPLY=()");
        lines.add("    cur=\"${COMP_WORDS[COMP_CWORD]}\"");
        lines.add("    prev=\"${COMP_WORDS[COMP_CWORD-1]}\"");
        lines.add("    base=\"${COMP_WORDS[1]}\"");
        // lines.add("    opts=\"" + commands + " " + genericOptions + "\"");
        lines.add("    cmds=\"" + commands + "\"");

        // basic commands as first argument
        lines.add("    if [[ ${#COMP_WORDS[@]} == 2 ]] ; then");
        lines.add("        COMPREPLY=( $(compgen -W \"${cmds} --help\" -- ${cur}) )");
        lines.add("        return 0");
        lines.add("    fi");

        // special treatment for help
        lines.add("    if [[ ${COMP_WORDS[1]} == '--help' ]] ; then");
        lines.add("        COMPREPLY=( $(compgen -W \"${cmds}\" -- ${cur}) )");
        lines.add("        return 0");
        lines.add("    fi");

        // for each special case
        lines.add("    case \"${prev}\" in");
        // case for file type option
        for (Option option : findByMetaVar("file")) {
            lines.add(String.format("        %s)", option.name()));
            lines.add("            COMPREPLY=( $(compgen -df ${cur}) )");
            lines.add("            return 0");
            lines.add("            ;;");
        }
        // case for directory type option
        for (Option option : findByMetaVar("dir")) {
            lines.add(String.format("        %s)", option.name()));
            lines.add("            COMPREPLY=( $(compgen -d ${cur}) )");
            lines.add("            return 0");
            lines.add("            ;;");
        }
        // case for url
        for (Option option : findByMetaVar("url")) {
            lines.add(String.format("        %s)", option.name()));
            lines.add("            COMPREPLY=( $(compgen -A hostname ${cur}) )");
            lines.add("            return 0");
            lines.add("            ;;");
        }
        lines.add("    esac");

        // for each command
        // TODO should eliminate prev appeared options reappearing
        lines.add("    case \"${base}\" in");
        // case for individual commands
        for (Map.Entry<String, List<Option>> entry : commandOptions.entrySet()) {
            String localVar = entry.getKey() + "_opts";
            lines.add(String.format("        %s)", entry.getKey()));
            lines.add(String.format("            local %s=\"%s\"", localVar,
                    optionsToString(entry.getValue())));
            lines.add(String
                    .format("            COMPREPLY=( $(compgen -W \"${%s}\" -- ${cur}) )",
                            localVar));
            lines.add("            return 0");
            lines.add("            ;;");
        }
        lines.add("    esac");

        lines.add("}");
        lines.add("complete -F _zanata " + commandName);

        Joiner lineJoiner = Joiner.on(System.getProperty("line.separator"));
        String fileContents = lineJoiner.join(lines);
        FileUtils.writeStringToFile(to, fileContents, Charsets.UTF_8);
    }

    private static String optionsToString(Iterable<Option> options) {
        return joiner.join(Iterables.transform(options, OptionToName.FUNCTION));
    }

    private Iterable<Option> findByMetaVar(final String expectedMetaVar) {
        return Iterables.filter(allOptions, new Predicate<Option>() {
            @Override
            public boolean apply(Option input) {
                // THIS IS NOT QUITE RELIABLE. but hey :)
                return input.metaVar().toLowerCase().contains(expectedMetaVar);
            }
        });
    }

    static enum OptionToName implements Function<Option, String> {
        FUNCTION;

        @Override
        public String apply(@Nonnull Option input) {
            return input.name();
        }
    }
}
