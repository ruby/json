require "bundler/gem_tasks"

require 'rbconfig'
include RbConfig

require 'rake/testtask'
class UndocumentedTestTask < Rake::TestTask
  def desc(*) end
end

PKG_VERSION       = File.foreach(File.join(__dir__, "lib/json/version.rb")) do |line|
  /^\s*VERSION\s*=\s*'(.*)'/ =~ line and break $1
end rescue nil

JAVA_DIR            = "java/src/json/ext"
JAVA_RAGEL_PATH     = "#{JAVA_DIR}/ParserConfig.rl"
JAVA_PARSER_SRC     = "#{JAVA_DIR}/ParserConfig.java"
JAVA_SOURCES        = FileList["#{JAVA_DIR}/*.java"]
JAVA_CLASSES        = []
JRUBY_PARSER_JAR    = File.expand_path("lib/json/ext/parser.jar")
JRUBY_GENERATOR_JAR = File.expand_path("lib/json/ext/generator.jar")

which = lambda { |c|
  w = `which #{c}`
  break w.chomp unless w.empty?
}

if RUBY_PLATFORM =~ /mingw|mswin/
  # cleans up Windows CI output
  RAGEL_CODEGEN     = %w[ragel].find(&which)
  RAGEL_DOTGEN      = %w[ragel].find(&which)
else
  RAGEL_CODEGEN     = %w[rlcodegen rlgen-cd ragel].find(&which)
  RAGEL_DOTGEN      = %w[rlgen-dot rlgen-cd ragel].find(&which)
end

file JAVA_PARSER_SRC => JAVA_RAGEL_PATH do
  cd JAVA_DIR do
    if RAGEL_CODEGEN == 'ragel'
      sh "ragel ParserConfig.rl -J -o ParserConfig.java"
    else
      sh "ragel -x ParserConfig.rl | #{RAGEL_CODEGEN} -J"
    end
  end
end

desc "Generate parser with ragel"
task :ragel => [JAVA_PARSER_SRC]

if defined?(RUBY_ENGINE) and RUBY_ENGINE == 'jruby'
  ENV['JAVA_HOME'] ||= [
    '/usr/local/java/jdk',
    '/usr/lib/jvm/java-6-openjdk',
    '/Library/Java/Home',
  ].find { |c| File.directory?(c) }
  if ENV['JAVA_HOME']
    warn " *** JAVA_HOME is set to #{ENV['JAVA_HOME'].inspect}"
    ENV['PATH'] = ENV['PATH'].split(/:/).unshift(java_path = "#{ENV['JAVA_HOME']}/bin") * ':'
    warn " *** java binaries are assumed to be in #{java_path.inspect}"
  else
    warn " *** JAVA_HOME was not set or could not be guessed!"
    exit 1
  end

  JRUBY_JAR = File.join(CONFIG["libdir"], "jruby.jar")
  if File.exist?(JRUBY_JAR)
    JAVA_SOURCES.each do |src|
      classpath = (Dir['java/lib/*.jar'] << 'java/src' << JRUBY_JAR) * ':'
      obj = src.sub(/\.java\Z/, '.class')
      file obj => src do
        sh 'javac', '-classpath', classpath, '-source', '1.8', '-target', '1.8', src
      end
      JAVA_CLASSES << obj
    end
  else
    warn "WARNING: Cannot find jruby in path => Cannot build jruby extension!"
  end

  desc "Compiling jruby extension"
  task :compile => [:ragel] + JAVA_CLASSES

  desc "Package the jruby gem"
  task :jruby_gem => :create_jar do
    mkdir_p 'pkg'
    sh "gem build -o pkg/json-#{PKG_VERSION}-java.gem json.gemspec"
  end

  UndocumentedTestTask.new do |t|
    t.name = :test
    t.test_files = FileList['test/json/*_test.rb']
    t.verbose = true
    t.options = '-v'
  end
  desc "Testing library (jruby)"
  task :test => [:create_jar ]

  file JRUBY_PARSER_JAR => :compile do
    cd 'java/src' do
      parser_classes = FileList[
        "json/ext/ByteList*.class",
        "json/ext/OptionsReader*.class",
        "json/ext/Parser*.class",
        "json/ext/RuntimeInfo*.class",
        "json/ext/StringDecoder*.class",
        "json/ext/Utils*.class"
      ]
      sh 'jar', 'cf', File.basename(JRUBY_PARSER_JAR), *parser_classes
      mv File.basename(JRUBY_PARSER_JAR), File.dirname(JRUBY_PARSER_JAR)
    end
  end

  desc "Create parser jar"
  task :create_parser_jar => JRUBY_PARSER_JAR

  file JRUBY_GENERATOR_JAR => :compile do
    cd 'java/src' do
      generator_classes = FileList[
        "json/ext/ByteList*.class",
        "json/ext/OptionsReader*.class",
        "json/ext/Generator*.class",
        "json/ext/RuntimeInfo*.class",
        "json/ext/StringEncoder*.class",
        "json/ext/Utils*.class"
      ]
      sh 'jar', 'cf', File.basename(JRUBY_GENERATOR_JAR), *generator_classes
      mv File.basename(JRUBY_GENERATOR_JAR), File.dirname(JRUBY_GENERATOR_JAR)
    end
  end

  desc "Create generator jar"
  task :create_generator_jar => JRUBY_GENERATOR_JAR

  desc "Create parser and generator jars"
  task :create_jar => [ :create_parser_jar, :create_generator_jar ]

  desc "Build all gems and archives for a new release of the jruby extension."
  task :build => [ :clean, :jruby_gem ]

  task :release => :build
else
  require 'rake/extensiontask'

  unless RUBY_ENGINE == 'truffleruby'
    Rake::ExtensionTask.new("json/ext/generator")
  end

  Rake::ExtensionTask.new("json/ext/parser")

  UndocumentedTestTask.new do |t|
    t.name = :test
    t.test_files = FileList['test/json/*_test.rb']
    t.verbose = true
    t.options = '-v'
  end

  desc "Testing library (extension)"
  task :test => [ :compile ]

  begin
    require "ruby_memcheck"
    RubyMemcheck::TestTask.new(valgrind: [ :compile, :test ]) do |t|
      t.test_files = FileList['test/json/*_test.rb']
      t.verbose = true
      t.options = '-v'
    end
  rescue LoadError
  end

  desc "Update the tags file"
  task :tags do
    system 'ctags', *Dir['**/*.{rb,c,h,java}']
  end

  desc "Create the gem packages"
  task :package do
    sh "gem build json.gemspec"
    mkdir_p 'pkg'
    mv "json-#{PKG_VERSION}.gem", 'pkg'
  end

  desc "Build all gems and archives for a new release of json"
  task :build => [ :clean, :package ]

  task :release => :build
end

desc "Compile in the the source directory"
task :default => [ :clean, :test ]
